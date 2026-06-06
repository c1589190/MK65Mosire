package com.mk65.motivation;

import com.mk65.config.MKConfig;

import java.util.*;

/**
 * 动机冲突检测器。
 * 计算两个输入token在历史上指向的行动分布之间的余弦距离。
 * 距离越大 → 冲突越高 → 说明两个token在历史上导向了不同的行动模式。
 *
 * 过滤策略：只有"在同一语境中反复共现但行动分布不同"的token对才值得LLM关注。
 * 碎片token（低频）和不共现的token对（来自不同语境）自然消解即可。
 */
public class ConflictDetector {

    /**
     * 在当前输入的所有token中，找出彼此行动分布冲突的token对。
     *
     * @param actionDistributions 每个输入token → 行动分布
     * @return 冲突对列表，按冲突度降序
     */
    public static List<ConflictPair> detectConflicts(
            Map<String, Map<String, Double>> actionDistributions) {

        List<ConflictPair> conflicts = new ArrayList<>();
        List<String> tokens = new ArrayList<>(actionDistributions.keySet());

        for (int i = 0; i < tokens.size(); i++) {
            for (int j = i + 1; j < tokens.size(); j++) {
                String a = tokens.get(i);
                String b = tokens.get(j);
                Map<String, Double> distA = actionDistributions.get(a);
                Map<String, Double> distB = actionDistributions.get(b);

                if (distA.isEmpty() || distB.isEmpty()) continue;

                double conflict = computeConflict(distA, distB);
                if (conflict > MKConfig.MOTIVATION_CONFLICT_THRESHOLD) {
                    conflicts.add(new ConflictPair(a, b, conflict, distA, distB));
                }
            }
        }

        conflicts.sort((c1, c2) -> Double.compare(c2.conflictScore, c1.conflictScore));
        return conflicts;
    }

    /**
     * 用共现系数和统计可靠性对冲突对重新评分，只保留最值得LLM关注的 Top-K。
     *
     * 评分公式: significance = conflictScore × reliability × coOccurBonus
     *   reliability = min(1.0, count(tokenA)/minCount) × min(1.0, count(tokenB)/minCount)
     *   coOccurBonus = 0.1 + 0.9 × (共现轮次数 / max(各自轮次数))
     *
     * @param rawConflicts    原始冲突对（已按冲突度降序）
     * @param tokenCounts     每个 token 在 CoMatrix 中的总出现次数（来自 SUM(count)）
     * @param occStats        从 Experiences 扫描的共现统计
     * @param maxPairs        最多保留多少个冲突对
     * @return 过滤后的冲突对（按 significance 降序）
     */
    public static List<ConflictPair> filterSignificant(
            List<ConflictPair> rawConflicts,
            Map<String, Integer> tokenCounts,
            MemoryManager.TokenOccurrenceStats occStats,
            int maxPairs) {

        if (rawConflicts.isEmpty()) return rawConflicts;
        if (rawConflicts.size() <= maxPairs) return rawConflicts;

        int minCount = MKConfig.MOTIVATION_CONFLICT_MIN_TOKEN_COUNT;
        Map<String, Integer> occ = occStats.occurrences();
        Map<String, Integer> coOcc = occStats.coOccurrences();

        // 计算每个冲突对的 significance
        List<ScoredConflict> scored = new ArrayList<>();
        for (ConflictPair c : rawConflicts) {
            int countA = tokenCounts.getOrDefault(c.tokenA(), 0);
            int countB = tokenCounts.getOrDefault(c.tokenB(), 0);

            // 统计可靠性：两个 token 都有足够样本
            double reliability = Math.min(1.0, (double) countA / minCount)
                               * Math.min(1.0, (double) countB / minCount);

            // 共现系数：两个 token 是否经常出现在同一轮
            int occA = occ.getOrDefault(c.tokenA(), 0);
            int occB = occ.getOrDefault(c.tokenB(), 0);
            String pairKey = c.tokenA().compareTo(c.tokenB()) <= 0
                    ? c.tokenA() + "|" + c.tokenB()
                    : c.tokenB() + "|" + c.tokenA();
            int coOccur = coOcc.getOrDefault(pairKey, 0);
            double maxOcc = Math.max(occA, occB);
            double coOccurRatio = maxOcc > 0 ? coOccur / maxOcc : 0.0;
            double coOccurBonus = 0.1 + 0.9 * coOccurRatio;

            double significance = c.conflictScore() * reliability * coOccurBonus;

            scored.add(new ScoredConflict(c, significance, countA, countB, coOccurRatio));
        }

        // 按 significance 降序，取 top-K
        scored.sort((a, b) -> Double.compare(b.significance, a.significance));
        List<ConflictPair> result = new ArrayList<>();
        for (int i = 0; i < Math.min(maxPairs, scored.size()); i++) {
            result.add(scored.get(i).conflict);
        }
        return result;
    }

    /** 内部评分记录 */
    private record ScoredConflict(
            ConflictPair conflict,
            double significance,
            int countA,
            int countB,
            double coOccurRatio
    ) {}

    /**
     * 两个行动分布之间的冲突度 = 1 - 余弦相似度。
     */
    static double computeConflict(Map<String, Double> distA, Map<String, Double> distB) {
        Set<String> allActions = new HashSet<>();
        allActions.addAll(distA.keySet());
        allActions.addAll(distB.keySet());

        double dot = 0, normA = 0, normB = 0;
        for (String action : allActions) {
            double va = distA.getOrDefault(action, 0.0);
            double vb = distB.getOrDefault(action, 0.0);
            dot += va * vb;
            normA += va * va;
            normB += vb * vb;
        }
        normA = Math.sqrt(normA);
        normB = Math.sqrt(normB);

        if (normA == 0 || normB == 0) return 0.0;
        return 1.0 - (dot / (normA * normB));
    }

    /**
     * 冲突对数据类。
     */
    public record ConflictPair(
            String tokenA,
            String tokenB,
            double conflictScore,
            Map<String, Double> distA,
            Map<String, Double> distB
    ) {}

    /**
     * 找和指定token实践倾向最近的其他token（等价token）。
     * 基于coMatrix行向量的余弦相似度。
     */
    public static Set<String> findEquivalents(String token, Map<String, Map<String, Double>> allDists) {
        Set<String> equivalents = new HashSet<>();
        Map<String, Double> targetRow = allDists.get(token);
        if (targetRow == null || targetRow.isEmpty()) return equivalents;

        double threshold = MKConfig.EQUIVALENT_TOKEN_THRESHOLD;
        for (Map.Entry<String, Map<String, Double>> e : allDists.entrySet()) {
            if (e.getKey().equals(token)) continue;
            if (e.getValue().isEmpty()) continue;
            double sim = 1.0 - computeConflict(targetRow, e.getValue());
            if (sim > threshold) {
                equivalents.add(e.getKey());
            }
        }
        return equivalents;
    }
}
