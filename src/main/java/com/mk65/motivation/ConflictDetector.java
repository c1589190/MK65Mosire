package com.mk65.motivation;

import com.mk65.config.MKConfig;

import java.util.*;

/**
 * 动机冲突检测器。
 * 计算两个输入token在历史上指向的行动分布之间的余弦距离。
 * 距离越大 → 冲突越高 → 说明两个token在历史上导向了不同的行动模式。
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
}
