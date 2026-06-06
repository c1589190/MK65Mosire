package com.mk65.motivation;

import com.mk65.core.PrepareActionPool;
import java.util.*;

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
        int maxExps = com.mk65.config.MKConfig.MOTIVATION_REPORT_MAX_EXPERIENCES;
        int expMaxChars = com.mk65.config.MKConfig.MOTIVATION_REPORT_EXP_MAX_CHARS;
        StringBuilder sb = new StringBuilder();
        sb.append("【动机报告】\n");

        if (!hasHistory) {
            sb.append("（无历史数据）\n");
            return sb.toString();
        }

        // 1. 行动方向 — 仅 top 3
        if (!overallVotes.isEmpty()) {
            sb.append("倾向: ");
            overallVotes.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .limit(3)
                    .forEach(e -> sb.append(e.getKey()).append("(").append(String.format("%.0f", e.getValue())).append(") "));
            sb.append("\n");
        }

        // 2. 关联历史经验 — top N, 每条限制字符数
        if (!autoMemories.isEmpty()) {
            sb.append("经验:\n");
            int count = 0;
            for (MemoryManager.ExpMatch m : autoMemories) {
                if (count >= maxExps) break;
                String text = m.actionText();
                if (text.length() > expMaxChars) text = text.substring(0, expMaxChars) + "...";
                String tag = m.jaccard() > 0.3 ? "🟢" : (m.jaccard() > 0.1 ? "🟡" : "⚪");
                sb.append(String.format("  %s [Exp#%d] sim=%.2f: %s\n", tag, m.id(), m.jaccard(), text));
                if (!m.toolNames().isEmpty())
                    sb.append("    → ").append(String.join(", ", m.toolNames())).append("\n");
                count++;
            }
            sb.append("\n");
        }

        // 3. 行动池 — 每单元一行
        if (!actionPoolList.isEmpty()) {
            sb.append("行动池:\n");
            for (PrepareActionPool.ActionSummary a : actionPoolList) {
                sb.append(String.format("  [%d] %.2f %s \"%s\"\n",
                        a.index(), a.score(), a.source(), a.preview()));
            }
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
