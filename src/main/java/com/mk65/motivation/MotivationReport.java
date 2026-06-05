package com.mk65.motivation;

import com.mk65.core.PrepareActionPool;
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
    private final Map<String, List<MemoryManager.ExpMatch>> conflictResolutions;
    private final Map<String, Set<String>> equivalentTokens;
    private final List<PrepareActionPool.ActionSummary> actionPoolList;
    private final boolean hasHistory;

    public MotivationReport(
            Map<String, Double> overallVotes,
            Map<String, Map<String, Double>> perTokenDistribution,
            List<ConflictDetector.ConflictPair> conflicts,
            Set<String> novelTokens,
            List<MemoryManager.ExpMatch> autoMemories,
            Map<String, List<MemoryManager.ExpMatch>> conflictResolutions,
            Map<String, Set<String>> equivalentTokens,
            List<PrepareActionPool.ActionSummary> actionPoolList,
            boolean hasHistory) {
        this.overallVotes = overallVotes;
        this.perTokenDistribution = perTokenDistribution;
        this.conflicts = conflicts != null ? conflicts : List.of();
        this.novelTokens = novelTokens != null ? novelTokens : Set.of();
        this.autoMemories = autoMemories != null ? autoMemories : List.of();
        this.conflictResolutions = conflictResolutions != null ? conflictResolutions : Map.of();
        this.equivalentTokens = equivalentTokens != null ? equivalentTokens : Map.of();
        this.actionPoolList = actionPoolList != null ? actionPoolList : List.of();
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
            sb.append("🔀 实践等价:\n");
            equivalentTokens.forEach((token, equivs) -> {
                if (!equivs.isEmpty()) sb.append(String.format("  [%s] ≈ %s\n", token, equivs));
            });
            sb.append("\n");
        }

        // 3. 冲突 + 历史解决方案
        if (!conflicts.isEmpty()) {
            sb.append("⚠️ 行动冲突:\n");
            for (ConflictDetector.ConflictPair c : conflicts) {
                sb.append(String.format("  [%s] vs [%s] — 冲突度: %.2f\n", c.tokenA(), c.tokenB(), c.conflictScore()));
                String pairKey = c.tokenA() + "|" + c.tokenB();
                List<MemoryManager.ExpMatch> resolutions = conflictResolutions.getOrDefault(pairKey, List.of());
                if (!resolutions.isEmpty()) {
                    sb.append(String.format("    📖 历史解决方案 (%d条):\n", resolutions.size()));
                    for (MemoryManager.ExpMatch r : resolutions) {
                        sb.append(String.format("       Exp#%d R%d: %s → %s\n", r.id(), r.roundNumber(),
                                r.actionText().length() > 60 ? r.actionText().substring(0, 60) + "..." : r.actionText(),
                                r.toolNames().isEmpty() ? "(纯文本)" : String.join(",", r.toolNames())));
                    }
                } else {
                    sb.append("    (无历史解决方案 — 首次遇到此对立组合)\n");
                }
            }
            sb.append("\n");
        }

        // 4. 关联历史经验
        if (!autoMemories.isEmpty()) {
            sb.append("📖 关联历史经验:\n");
            for (MemoryManager.ExpMatch m : autoMemories) {
                sb.append(String.format("  相似度 %.2f [Exp#%d R%d]: %s\n", m.jaccard(), m.id(), m.roundNumber(),
                        m.actionText().length() > 100 ? m.actionText().substring(0, 100) + "..." : m.actionText()));
                if (!m.toolNames().isEmpty())
                    sb.append("    → 工具: ").append(String.join(", ", m.toolNames())).append("\n");
            }
            sb.append("\n");
        }

        // 5. 行动池
        if (!actionPoolList.isEmpty()) {
            sb.append("📋 当前行动池（按选择评分排序，可在finish_action中用select_next指定下一个）:\n");
            for (PrepareActionPool.ActionSummary a : actionPoolList) {
                sb.append(String.format("  [%d] 得分=%.2f | %s — \"%s\"\n",
                        a.index(), a.score(), a.source(), a.preview()));
                sb.append(String.format("      ⏳等待:%d轮 | 🔥外源:%.2f | 💡内源:%.2f\n",
                        a.waitingRounds(), a.exogenous(), a.endogenous()));
                if (a.novelTokens() > 0)
                    sb.append(String.format("      🆕新颖token: %d个 | ⚠️对立: %s\n", a.novelTokens(), a.oppositions()));
            }
            sb.append("\n");
        }

        // 6. 实践态度
        if (!overallVotes.isEmpty() || !conflicts.isEmpty()) {
            sb.append("🎯 实践态度:\n");
            double maxW = overallVotes.values().stream().max(Double::compare).orElse(0.0);
            sb.append(String.format("  行动把握度: %s\n", maxW > 5 ? "强" : (maxW > 1 ? "中" : "弱")));
            double maxJ = autoMemories.stream().mapToDouble(MemoryManager.ExpMatch::jaccard).max().orElse(0);
            sb.append(String.format("  场景熟悉度: %s\n", maxJ > 0.3 ? "高" : (maxJ > 0.1 ? "中" : "低")));

            if (!conflicts.isEmpty()) {
                boolean hasRes = conflictResolutions.values().stream().anyMatch(l -> !l.isEmpty());
                sb.append("  认知冲突: ").append(hasRes ? "有(有历史方案可供参考)" : "有(无历史方案, 需主动求解)").append("\n");
            } else {
                sb.append("  认知冲突: 无 → 惯性处理即可\n");
            }
            if (!novelTokens.isEmpty())
                sb.append(String.format("  新异token: %d个: %s\n", novelTokens.size(), novelTokens));
            sb.append("\n");
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
