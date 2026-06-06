package com.mk65.motivation;

import com.mk65.core.PrepareActionPool;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 动机报告 — 每轮注入 LLM prompt 的注意力引导数据。
 */
@Slf4j
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
        StringBuilder sb = new StringBuilder();
        sb.append("【动机报告】\n");

        if (!hasHistory) {
            sb.append("（无历史数据）\n");
            return sb.toString();
        }

        // 1. 行动倾向 — top 3
        if (!overallVotes.isEmpty()) {
            sb.append("倾向: ");
            overallVotes.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .limit(3)
                    .forEach(e -> sb.append(e.getKey()).append("(").append(String.format("%.0f", e.getValue())).append(") "));
            sb.append("\n");
        }

        // 2. 行动冲突 — LLM 要处理的核心矛盾（不截断）
        if (!conflicts.isEmpty()) {
            sb.append("⚠️ 行动冲突:\n");
            for (ConflictDetector.ConflictPair c : conflicts) {
                sb.append(String.format("  [%s] vs [%s] — 冲突度: %.2f\n", c.tokenA(), c.tokenB(), c.conflictScore()));
                String pairKey = c.tokenA() + "|" + c.tokenB();
                List<MemoryManager.ExpMatch> resolutions = conflictResolutions.getOrDefault(pairKey, List.of());
                if (!resolutions.isEmpty()) {
                    sb.append(String.format("    历史方案 (%d条):\n", resolutions.size()));
                    for (MemoryManager.ExpMatch r : resolutions) {
                        sb.append(String.format("      Exp#%d R%d: %s → %s\n", r.id(), r.roundNumber(),
                                r.actionText(), r.toolNames().isEmpty() ? "(纯文本)" : String.join(",", r.toolNames())));
                    }
                }
            }
            sb.append("\n");
        }

        // 3. 关联历史经验 — top N, 完整文本
        if (!autoMemories.isEmpty()) {
            sb.append("经验:\n");
            int count = 0;
            for (MemoryManager.ExpMatch m : autoMemories) {
                if (count >= maxExps) break;
                String tag = m.jaccard() > 0.3 ? "🟢" : (m.jaccard() > 0.1 ? "🟡" : "⚪");
                sb.append(String.format("  %s [Exp#%d] sim=%.2f: %s\n", tag, m.id(), m.jaccard(), m.actionText()));
                if (!m.toolNames().isEmpty())
                    sb.append("    → ").append(String.join(", ", m.toolNames())).append("\n");
                count++;
            }
            sb.append("\n");
        }

        // 4. 行动池 — 每单元一行
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

    /** 诊断：打印报告各组件的字符数，方便定位膨胀源 */
    public void logComponentSizes() {
        StringBuilder sb = new StringBuilder();
        sb.append("倾向: ");
        overallVotes.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(3)
                .forEach(e -> sb.append(e.getKey()).append("(").append(String.format("%.0f", e.getValue())).append(") "));
        int votesSize = sb.length();

        int conflictsSize = 0;
        int resolutionChars = 0;
        if (!conflicts.isEmpty()) {
            StringBuilder cs = new StringBuilder();
            for (ConflictDetector.ConflictPair c : conflicts) {
                cs.append(String.format("[%s]vs[%s]:%.2f ", c.tokenA(), c.tokenB(), c.conflictScore()));
                String pairKey = c.tokenA() + "|" + c.tokenB();
                List<MemoryManager.ExpMatch> resolutions = conflictResolutions.getOrDefault(pairKey, List.of());
                cs.append(String.format("(方案%d条) ", resolutions.size()));
                for (MemoryManager.ExpMatch r : resolutions) {
                    resolutionChars += r.actionText().length();
                }
            }
            conflictsSize = cs.length();
        }

        int expSize = 0;
        if (!autoMemories.isEmpty()) {
            for (MemoryManager.ExpMatch m : autoMemories) {
                expSize += m.actionText().length();
                if (!m.toolNames().isEmpty()) expSize += String.join(",", m.toolNames()).length();
            }
        }

        int poolSize = actionPoolList.size();

        int distTokens = perTokenDistribution.size();
        int eqSize = equivalentTokens.size();
        int novelSize = novelTokens.size();

        log.info("[Report] 📏 组件尺寸: votes={}chars, conflicts={}chars(方案内容{}chars), exps={}chars(total{}条), pool={}条 | "
                + "仅计算未注入: distTokens={}, equivs={}, novels={}",
                votesSize, conflictsSize, resolutionChars, expSize, autoMemories.size(), poolSize,
                distTokens, eqSize, novelSize);
    }

    // getters
    public Map<String, Double> getOverallVotes() { return overallVotes; }
    public Map<String, Map<String, Double>> getPerTokenDist() { return perTokenDistribution; }
    public List<ConflictDetector.ConflictPair> getConflicts() { return conflicts; }
    public Set<String> getNovelTokens() { return novelTokens; }
    public List<MemoryManager.ExpMatch> getAutoMemories() { return autoMemories; }
    public boolean hasHistory() { return hasHistory; }
}
