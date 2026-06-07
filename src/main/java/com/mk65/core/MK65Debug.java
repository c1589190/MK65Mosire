package com.mk65.core;

import com.mk65.motivation.ConflictDetector;
import com.mk65.motivation.MemoryManager;
import com.mk65.motivation.MotivationReport;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MK65 调试日志工具。
 * 集中输出动机系统全链路的可读日志，方便调试调整参数。
 */
@Slf4j
public class MK65Debug {

    private static volatile boolean enabled = false;

    public static void enable()  { enabled = true; }
    public static void disable() { enabled = false; }
    public static boolean isEnabled() { return enabled; }

    // ═══════════════════════════════════════════
    // 每轮入口
    // ═══════════════════════════════════════════

    public static void roundStart(int round, String source, String rawText) {
        if (!enabled) return;
        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║ 第{}轮 — 来源={}", round, source);
        log.info("║ 原文: {}", rawText.length() > 200 ? rawText.substring(0, 200) + "..." : rawText);
    }

    // ═══════════════════════════════════════════
    // 分词结果
    // ═══════════════════════════════════════════

    public static void inputTokens(String actionText, List<String> tokens) {
        if (!enabled) return;
        log.info("╟─ 输入分词 ──────────────────────────────────────────────");
        log.info("║ ActionText ({}chars): {}", actionText.length(),
                actionText.length() > 200 ? actionText.substring(0, 200) + "..." : actionText);
        log.info("║ → {}个token: {}", tokens.size(), tokens);
    }

    public static void actionTokens(List<String> encoded, List<String> textRecords) {
        if (!enabled) return;
        log.info("╟─ 行动编码 ──────────────────────────────────────────────");
        log.info("║ encode(tool_calls): {}", encoded);
        log.info("║ encodeTextRecord:   {}", textRecords);
    }

    // ═══════════════════════════════════════════
    // 共现矩阵
    // ═══════════════════════════════════════════

    public static void coMatrixUpdate(List<String> uniqueInput, List<String> uniqueAction) {
        if (!enabled) return;
        log.info("╟─ 共现矩阵更新 ──────────────────────────────────────────");
        log.info("║ 输入(token×{}): {}", uniqueInput.size(), uniqueInput);
        log.info("║ 行动(token×{}): {}", uniqueAction.size(), uniqueAction);
        for (String it : uniqueInput) {
            for (String at : uniqueAction) {
                log.info("║   [{}] × [{}] → +1", it, at);
            }
        }
    }

    // ═══════════════════════════════════════════
    // 动机查询
    // ═══════════════════════════════════════════

    public static void motivationQuery(Map<String, Map<String, Double>> dist,
                                        Set<String> novelTokens,
                                        List<ConflictDetector.TokenConflict> conflictTokens,
                                        Map<String, Double> overallVotes) {
        if (!enabled) return;
        log.info("╟─ 动机查询 ──────────────────────────────────────────────");

        // 行动分布
        log.info("║ 各token行动分布:");
        if (dist.isEmpty()) {
            log.info("║   (无历史数据)");
        } else {
            dist.forEach((token, actions) -> {
                String top3 = actions.entrySet().stream()
                        .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                        .limit(3)
                        .map(e -> e.getKey() + "(" + String.format("%.1f", e.getValue()) + ")")
                        .reduce((a, b) -> a + ", " + b).orElse("");
                log.info("║   [{}] → {}", token, top3);
            });
        }

        // 综合投票
        log.info("║ 综合行动倾向:");
        if (overallVotes.isEmpty()) {
            log.info("║   (无)");
        } else {
            overallVotes.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .forEach(e -> log.info("║   {} → {:.1f}", e.getKey(), e.getValue()));
        }

        // 新异token
        log.info("║ 新异token: {}", novelTokens.isEmpty() ? "(无)" : novelTokens);

        // 冲突token（度数排名）
        log.info("║ 冲突Token（按冲突广度排序）:");
        if (conflictTokens.isEmpty()) {
            log.info("║   (无冲突)");
        } else {
            for (ConflictDetector.TokenConflict tc : conflictTokens) {
                log.info("║   [{}] 冲突{}个: {}",
                        tc.token(), tc.conflictCount(),
                        String.join(", ", tc.conflictsWith()));
            }
        }
    }

    // ═══════════════════════════════════════════
    // 经验检索
    // ═══════════════════════════════════════════

    public static void autoMemories(List<MemoryManager.ExpMatch> matches) {
        if (!enabled) return;
        log.info("╟─ 自动经验检索 (Jaccard) ────────────────────────────────");
        if (matches.isEmpty()) {
            log.info("║   (无匹配经验)");
        } else {
            for (MemoryManager.ExpMatch m : matches) {
                log.info("║   Jaccard={:.3f} Exp#{} R{}: {}",
                        m.jaccard(), m.id(), m.roundNumber(),
                        m.actionText().length() > 80
                                ? m.actionText().substring(0, 80) + "..."
                                : m.actionText());
            }
        }
    }

    // ═══════════════════════════════════════════
    // 经验录制
    // ═══════════════════════════════════════════

    public static void experienceRecorded(int expId, int round, List<String> toolNames,
                                           List<String> inputTokens, List<String> actionTokens) {
        if (!enabled) return;
        log.info("╟─ 经验录制 ──────────────────────────────────────────────");
        log.info("║ Exp#{}, 第{}轮, 工具: {}", expId, round, toolNames.isEmpty() ? "(纯文本)" : toolNames);
        log.info("║ 输入token({}): {}", inputTokens.size(), inputTokens);
        log.info("║ 行动token({}): {}", actionTokens.size(), actionTokens);
    }

    // ═══════════════════════════════════════════
    // LLM调用
    // ═══════════════════════════════════════════

    public static void llmCall(int globalMsgCount, String userMessage) {
        if (!enabled) return;
        log.info("╟─ LLM调用 ───────────────────────────────────────────────");
        log.info("║ 全局消息数: {}", globalMsgCount);
        // 只打印动机报告部分
        int reportStart = userMessage.indexOf("【动机报告");
        if (reportStart >= 0) {
            String report = userMessage.substring(reportStart);
            for (String line : report.split("\n")) {
                log.info("║ {}", line.length() > 120 ? line.substring(0, 120) + "..." : line);
            }
        }
    }

    public static void llmResponse(long elapsedMs, int toolCallCount, String thoughts) {
        if (!enabled) return;
        log.info("╟─ LLM响应 ({}ms) ─────────────────────────────────────────", elapsedMs);
        log.info("║ 工具调用: {}个", toolCallCount);
        if (thoughts != null && !thoughts.isBlank()) {
            String t = thoughts.length() > 300 ? thoughts.substring(0, 300) + "..." : thoughts;
            log.info("║ thoughts: {}", t);
        }
    }

    // ═══════════════════════════════════════════
    // 池状态
    // ═══════════════════════════════════════════

    public static void poolState(int poolSize, String nextActionSummary) {
        if (!enabled) return;
        log.info("╟─ 行动池 ────────────────────────────────────────────────");
        log.info("║ 池内待处理: {}个", poolSize);
        if (nextActionSummary != null) {
            log.info("║ 下一个: {}", nextActionSummary);
        }
    }

    // ═══════════════════════════════════════════
    // 每轮结束
    // ═══════════════════════════════════════════

    public static void roundEnd(int round, int inputTokenCount, int actionTokenCount, int matrixRound) {
        if (!enabled) return;
        log.info("║ 第{}轮完成 — 输入token={}, 行动token={}, 矩阵轮次={}",
                round, inputTokenCount, actionTokenCount, matrixRound);
        log.info("╚══════════════════════════════════════════════════════════════╝");
    }
}
