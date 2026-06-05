package com.mk65.motivation;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 动机报告 — 每轮注入 LLM prompt 的注意力引导数据。
 */
public class MotivationReport {

    private final Map<String, Double> overallVotes;
    private final Map<String, Map<String, Double>> perTokenDistribution;
    private final List<ConflictDetector.ConflictPair> conflicts;
    private final Set<String> novelTokens;
    private final List<MemoryManager.ExpMatch> autoMemories;
    // ★ 每个冲突对的历史解决方案
    private final Map<String, List<MemoryManager.ExpMatch>> conflictResolutions;
    // ★ 等价token映射
    private final Map<String, Set<String>> equivalentTokens;
    private final boolean hasHistory;

    public MotivationReport(
            Map<String, Double> overallVotes,
            Map<String, Map<String, Double>> perTokenDistribution,
            List<ConflictDetector.ConflictPair> conflicts,
            Set<String> novelTokens,
            List<MemoryManager.ExpMatch> autoMemories,
            Map<String, List<MemoryManager.ExpMatch>> conflictResolutions,
            Map<String, Set<String>> equivalentTokens,
            boolean hasHistory) {
        this.overallVotes = overallVotes;
        this.perTokenDistribution = perTokenDistribution;
        this.conflicts = conflicts != null ? conflicts : List.of();
        this.novelTokens = novelTokens != null ? novelTokens : Set.of();
        this.autoMemories = autoMemories != null ? autoMemories : List.of();
        this.conflictResolutions = conflictResolutions != null ? conflictResolutions : Map.of();
        this.equivalentTokens = equivalentTokens != null ? equivalentTokens : Map.of();
        this.hasHistory = hasHistory;
    }

    public String toPromptBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("【动机报告 — 白箱统计】\n\n");

        if (!hasHistory) {
            sb.append("（无历史数据，自由探索模式。）\n");
            return sb.toString();
        }

        // 1. 行动方向
        if (!overallVotes.isEmpty()) {
            sb.append("综合行动倾向:\n");
            overallVotes.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .limit(8)
                    .forEach(e -> sb.append(String.format("  %-40s 权重: %.1f\n", e.getKey(), e.getValue())));

            sb.append("\n各token行动分布:\n");
            perTokenDistribution.forEach((token, dist) -> {
                if (dist.isEmpty()) return;
                String top3 = dist.entrySet().stream()
                        .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                        .limit(3)
                        .map(e -> e.getKey() + "(" + String.format("%.0f", e.getValue()) + ")")
                        .collect(Collectors.joining(", "));
                sb.append(String.format("  [%s] → %s\n", token, top3));
            });
            sb.append("\n");
        }

        // 2. 等价token
        if (!equivalentTokens.isEmpty()) {
            sb.append("🔀 实践等价（不同token指向相同行动）:\n");
            equivalentTokens.forEach((token, equivs) -> {
                if (!equivs.isEmpty()) {
                    sb.append(String.format("  [%s] ≈ %s\n", token, equivs));
                }
            });
            sb.append("\n");
        }

        // 3. 冲突 + 历史解决方案
        if (!conflicts.isEmpty()) {
            sb.append("⚠️ 行动冲突:\n");
            for (ConflictDetector.ConflictPair c : conflicts) {
                sb.append(String.format("  [%s] vs [%s] — 冲突度: %.2f\n",
                        c.tokenA(), c.tokenB(), c.conflictScore()));

                // 等价范围
                Set<String> equivA = equivalentTokens.getOrDefault(c.tokenA(), Set.of());
                Set<String> equivB = equivalentTokens.getOrDefault(c.tokenB(), Set.of());
                if (!equivA.isEmpty() || !equivB.isEmpty()) {
                    sb.append(String.format("    等价: [%s]≈%s, [%s]≈%s\n",
                            c.tokenA(), equivA, c.tokenB(), equivB));
                }

                // 历史解决方案
                String pairKey = c.tokenA() + "|" + c.tokenB();
                List<MemoryManager.ExpMatch> resolutions = conflictResolutions.getOrDefault(pairKey, List.of());
                if (!resolutions.isEmpty()) {
                    sb.append(String.format("    📖 历史解决方案 (%d条):\n", resolutions.size()));
                    for (MemoryManager.ExpMatch r : resolutions) {
                        sb.append(String.format("       Exp#%d R%d: %s → %s\n",
                                r.id(), r.roundNumber(),
                                r.actionText().length() > 60 ? r.actionText().substring(0, 60) + "..." : r.actionText(),
                                r.toolNames().isEmpty() ? "(纯文本)" : String.join(",", r.toolNames())));
                    }
                } else {
                    sb.append("    (无历史解决方案 — 首次遇到此对立的组合)\n");
                }
            }
            sb.append("\n");
        }

        // 4. 关联历史经验
        if (!autoMemories.isEmpty()) {
            sb.append("📖 关联历史经验（Jaccard场景匹配）:\n");
            for (MemoryManager.ExpMatch m : autoMemories) {
                sb.append(String.format("  相似度 %.2f [Exp#%d R%d]: %s\n",
                        m.jaccard(), m.id(), m.roundNumber(),
                        m.actionText().length() > 100 ? m.actionText().substring(0, 100) + "..." : m.actionText()));
                if (!m.toolNames().isEmpty()) {
                    sb.append("    → 工具: ").append(String.join(", ", m.toolNames())).append("\n");
                }
            }
            sb.append("\n");
        }

        // 5. 实践态度
        if (!overallVotes.isEmpty() || !conflicts.isEmpty()) {
            sb.append("🎯 实践态度:\n");
            // 行动把握度
            double maxActionWeight = overallVotes.values().stream().max(Double::compare).orElse(0.0);
            String actionConfidence = maxActionWeight > 5 ? "强" : (maxActionWeight > 1 ? "中" : "弱");
            sb.append(String.format("  行动把握度: %s\n", actionConfidence));

            // 场景熟悉度
            double maxJaccard = autoMemories.stream().mapToDouble(MemoryManager.ExpMatch::jaccard).max().orElse(0);
            String sceneFamiliarity = maxJaccard > 0.3 ? "高" : (maxJaccard > 0.1 ? "中" : "低");
            sb.append(String.format("  场景熟悉度: %s (maxJaccard=%.2f)\n", sceneFamiliarity, maxJaccard));

            // 认知冲突状态
            if (!conflicts.isEmpty()) {
                double maxConflict = conflicts.stream().mapToDouble(ConflictDetector.ConflictPair::conflictScore).max().orElse(0);
                boolean hasResolutions = conflictResolutions.values().stream().anyMatch(l -> !l.isEmpty());
                if (hasResolutions) {
                    sb.append("  认知冲突: 存在(有历史解决方案可供参考)\n");
                    sb.append("  → 建议: 参照历史解决方案的模式处理，根据当前差异微调\n");
                } else {
                    sb.append(String.format("  认知冲突: 存在(最大冲突度=%.2f，无历史解决方案)\n", maxConflict));
                    sb.append("  → 建议: 对立方向需要你独立判断。选择一方执行，本轮经验将成为未来解决方案。\n");
                }
            } else {
                sb.append("  认知冲突: 无\n");
                sb.append("  → 建议: 按行动倾向执行，惯性处理即可。\n");
            }

            // 新异程度
            if (!novelTokens.isEmpty()) {
                sb.append(String.format("  新异程度: %d个新token: %s\n", novelTokens.size(), novelTokens));
                sb.append("  → 新token无历史统计，你的行动会成为它们的第一个模板。\n");
            }
            sb.append("\n");
        }

        // 6. 新异token
        if (!novelTokens.isEmpty()) {
            sb.append("🆕 新异token: ").append(String.join(", ", novelTokens)).append("\n\n");
        }

        return sb.toString();
    }

    // getters
    public Map<String, Double> getOverallVotes() { return overallVotes; }
    public Map<String, Map<String, Double>> getPerTokenDist() { return perTokenDistribution; }
    public List<ConflictDetector.ConflictPair> getConflicts() { return conflicts; }
    public Set<String> getNovelTokens() { return novelTokens; }
    public List<MemoryManager.ExpMatch> getAutoMemories() { return autoMemories; }
    public boolean hasHistory() { return hasHistory; }
}
