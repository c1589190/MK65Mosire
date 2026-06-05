package com.mk65.motivation;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 动机报告 — 每轮注入 LLM prompt 的注意力引导数据。
 *
 * 包含：
 * 1. 行动方向投票（每个输入token在历史上指向什么行动，加和得到综合倾向）
 * 2. 冲突检测结果（输入内哪些token的行动方向互相矛盾）
 * 3. 新异token标记（首次出现或极罕见的token）
 */
public class MotivationReport {

    // 综合行动倾向（所有输入 token 的加权行动 token 频率）
    private final Map<String, Double> overallVotes;
    // 每个输入 token 各自的 Top 行动分布
    private final Map<String, Map<String, Double>> perTokenDistribution;
    // 冲突对
    private final List<ConflictDetector.ConflictPair> conflicts;
    // 新异 token
    private final Set<String> novelTokens;
    // 自动关联的历史经验（Jaccard匹配）
    private final List<MemoryManager.ExpMatch> autoMemories;
    // 是否有任何历史数据
    private final boolean hasHistory;

    public MotivationReport(
            Map<String, Double> overallVotes,
            Map<String, Map<String, Double>> perTokenDistribution,
            List<ConflictDetector.ConflictPair> conflicts,
            Set<String> novelTokens,
            List<MemoryManager.ExpMatch> autoMemories,
            boolean hasHistory) {
        this.overallVotes = overallVotes;
        this.perTokenDistribution = perTokenDistribution;
        this.conflicts = conflicts;
        this.novelTokens = novelTokens;
        this.autoMemories = autoMemories != null ? autoMemories : List.of();
        this.hasHistory = hasHistory;
    }

    /**
     * 生成动机报告 — 纯文本，直接注入 LLM prompt。
     */
    public String toPromptBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("【动机报告 — 白箱统计】\n\n");

        if (!hasHistory) {
            sb.append("（无历史数据，自由探索模式。这是首批输入，请根据你的判断自行决定行动。）\n");
            return sb.toString();
        }

        // 1. 行动方向投票
        if (!overallVotes.isEmpty()) {
            sb.append("综合行动倾向（当前输入token在历史上指向的行动方向汇总）:\n");
            // 按权重降序取 Top 8
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
                sb.append(String.format("  %-30s → %s\n", "[" + token + "]", top3));
            });
            sb.append("\n");
        } else {
            sb.append("综合行动倾向: （当前输入无历史记录）\n\n");
        }

        // 2. 冲突
        if (!conflicts.isEmpty()) {
            sb.append("⚠️ 输入内部行动冲突:\n");
            for (ConflictDetector.ConflictPair c : conflicts) {
                sb.append(String.format("  [%s] vs [%s] — 冲突度: %.2f\n", c.tokenA(), c.tokenB(), c.conflictScore()));
                String actA = topAction(c.distA());
                String actB = topAction(c.distB());
                sb.append(String.format("    [%s] 指向: %s\n", c.tokenA(), actA));
                sb.append(String.format("    [%s] 指向: %s\n", c.tokenB(), actB));
            }
            sb.append("  建议: 两个方向矛盾，可能需要先澄清或分步处理。\n\n");
        }

        // 3. 关联经验
        if (!autoMemories.isEmpty()) {
            sb.append("📖 关联历史经验（Jaccard场景匹配，最像的过往实践）:\n");
            for (MemoryManager.ExpMatch m : autoMemories) {
                sb.append(String.format("  相似度 %.2f ", m.jaccard()));
                sb.append(m.toPromptLine().trim()).append("\n");
            }
            sb.append("\n");
        }

        // 4. 新异token
        if (!novelTokens.isEmpty()) {
            sb.append("🆕 新异token（首次出现或极罕见，可能指向新任务类型）:\n");
            sb.append("  ").append(String.join(", ", novelTokens)).append("\n");
            sb.append("  建议: 这些token无历史参考，请结合上下文自行判断行动方向。\n\n");
        }

        return sb.toString();
    }

    private static String topAction(Map<String, Double> dist) {
        return dist.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> e.getKey() + "(" + String.format("%.0f", e.getValue()) + ")")
                .orElse("无数据");
    }

    // getters
    public Map<String, Double> getOverallVotes() { return overallVotes; }
    public Map<String, Map<String, Double>> getPerTokenDist() { return perTokenDistribution; }
    public List<ConflictDetector.ConflictPair> getConflicts() { return conflicts; }
    public Set<String> getNovelTokens() { return novelTokens; }
    public List<MemoryManager.ExpMatch> getAutoMemories() { return autoMemories; }
    public boolean hasHistory() { return hasHistory; }
}
