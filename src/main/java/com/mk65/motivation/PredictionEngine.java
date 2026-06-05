package com.mk65.motivation;

import com.mk65.config.MKConfig;
import com.mk65.config.MKDB;
import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.util.*;

/**
 * 预测引擎 — B→C 时间承接预测。
 *
 * 不预测"这个 ActionText 应该调什么工具"（共现矩阵已做）。
 * 预测"根据本轮经验，下一轮可能会遇到什么输入"。
 *
 * 算法：
 * 1. 用当前经验的 token 集合，Jaccard 匹配历史中相似的轮次（B对象）
 * 2. 对每个匹配到的 B，取它的下一条经验（C对象）的 input_tokens
 * 3. 聚合所有 C 对象的 tokens → 这就是"预期接下来会发生什么"
 * 4. 下一轮实际到来时，比较实际 tokens 和预期 tokens → 认知压
 */
@Slf4j
public class PredictionEngine {

    private static volatile PredictionEngine INSTANCE;

    // 上一轮的经验ID，用于构建时间链
    private int lastExpId = -1;
    // 上一轮结束后生成的预期token集合
    private Set<String> lastPredictedTokens = Set.of();
    // 上次预期的把握度（匹配效率因子）
    private double lastPredictionConfidence = 0.0;

    private PredictionEngine() {}

    public static PredictionEngine getInstance() {
        if (INSTANCE == null) {
            synchronized (PredictionEngine.class) {
                if (INSTANCE == null) INSTANCE = new PredictionEngine();
            }
        }
        return INSTANCE;
    }

    // ═══════════════════════════════════════════
    // 预测：本轮结束 → 预测下一轮
    // ═══════════════════════════════════════════

    /**
     * 根据当前经验（刚录制完成的），预测下一轮可能会遇到什么。
     * 返回 PredictionResult，包含预期 token 集合、把握度、匹配到的 B 对象数量。
     */
    public PredictionResult predict(int currentExpId, List<String> currentInputTokens,
                                     List<String> currentActionTokens, List<String> currentToolNames) {
        // 合并当前经验的所有特征 token（输入+行动+工具名）
        Set<String> currentFeatures = new HashSet<>(currentInputTokens);
        currentFeatures.addAll(currentActionTokens);
        for (String tn : currentToolNames) {
            currentFeatures.add("tool:" + tn);
        }

        // 扫描历史经验，找与当前经验相似的那些轮次
        String sql = "SELECT id, round_number, input_tokens, action_tokens, tool_names FROM Experiences ORDER BY id DESC LIMIT " + MKConfig.MEMORY_AUTO_RECALL_SCAN_LIMIT;

        List<BMatch> bMatches = new ArrayList<>();

        try (Connection conn = MKDB.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                int expId = rs.getInt("id");
                if (expId >= currentExpId) continue; // 不匹配自己

                Set<String> expFeatures = new HashSet<>();
                expFeatures.addAll(fromJson(rs.getString("input_tokens")));
                expFeatures.addAll(fromJson(rs.getString("action_tokens")));
                for (String tn : fromJson(rs.getString("tool_names"))) {
                    expFeatures.add("tool:" + tn);
                }

                double jaccard = jaccard(currentFeatures, expFeatures);
                if (jaccard > 0.05) { // 极低阈值，捞更多候选
                    bMatches.add(new BMatch(expId, rs.getInt("round_number"), jaccard));
                }
            }
        } catch (SQLException e) {
            log.error("[Prediction] B匹配失败", e);
            return PredictionResult.EMPTY;
        }

        if (bMatches.isEmpty()) {
            lastPredictedTokens = Set.of();
            lastPredictionConfidence = 0.0;
            return PredictionResult.EMPTY;
        }

        // 按Jaccard降序，取 top 10 个 B 对象
        bMatches.sort((a, b) -> Double.compare(b.jaccard, a.jaccard));
        if (bMatches.size() > 10) bMatches = bMatches.subList(0, 10);

        // 最高相似度 = 把握度（匹配效率因子）
        double confidence = bMatches.get(0).jaccard;

        // 对每个 B 对象，查它的下一条经验（C对象）
        // 注意：下一条经验在数据库中的 id = B.id + 1（因为经验按时间顺序插入）
        // 但跨轮次时id可能不连续（其他来源的输入可能被跳过），所以用 round_number + 1
        Map<String, Double> predictedTokenWeights = new HashMap<>();
        int cCount = 0;

        for (BMatch b : bMatches) {
            // 找 B 的下一条经验
            String nextSql = "SELECT id, input_tokens, action_tokens FROM Experiences WHERE round_number = ? LIMIT 1";
            try (Connection conn = MKDB.getConnection();
                 PreparedStatement ps = conn.prepareStatement(nextSql)) {
                ps.setInt(1, b.roundNumber + 1);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        cCount++;
                        List<String> nextInput = fromJson(rs.getString("input_tokens"));
                        List<String> nextAction = fromJson(rs.getString("action_tokens"));

                        // 权重 = B的Jaccard相似度 / 总匹配数
                        double weight = b.jaccard / bMatches.size();

                        for (String t : nextInput) {
                            predictedTokenWeights.merge(t, weight, Double::sum);
                        }
                        for (String t : nextAction) {
                            predictedTokenWeights.merge("action:" + t, weight, Double::sum);
                        }
                    }
                }
            } catch (SQLException e) {
                log.debug("[Prediction] C对象查询失败 B.id={}", b.expId);
            }
        }

        // 归一化
        double maxW = predictedTokenWeights.values().stream().max(Double::compare).orElse(1.0);
        Set<String> predictedTokens = new HashSet<>();
        for (Map.Entry<String, Double> e : predictedTokenWeights.entrySet()) {
            if (e.getValue() / maxW > 0.15) { // 只保留显著性较高的
                predictedTokens.add(e.getKey());
            }
        }

        lastPredictedTokens = predictedTokens;
        lastPredictionConfidence = confidence;
        lastExpId = currentExpId;

        log.info("[Prediction] 🔮 预测生成: {}个B对象→{}个C对象, 把握度={:.2f}, 预期token={}",
                bMatches.size(), cCount, confidence, predictedTokens.size());

        return new PredictionResult(predictedTokens, confidence, bMatches.size(), cCount);
    }

    // ═══════════════════════════════════════════
    // 检验：下一轮来了 → 比较预期 vs 实际
    // ═══════════════════════════════════════════

    /**
     * 比较实际到来的输入和上一轮的预期。
     * 返回认知压：正值=意外（实际有而预期没有），负值=过拟合（预期有而实际没有）。
     */
    public PressureReport checkPressure(List<String> actualInputTokens) {
        if (lastPredictedTokens.isEmpty()) return PressureReport.NONE;

        Set<String> actual = new HashSet<>(actualInputTokens);
        Set<String> predicted = lastPredictedTokens;

        // 意外：实际有，预期没有
        Set<String> surprise = new HashSet<>(actual);
        surprise.removeAll(predicted);

        // 过拟合/违和：预期有，实际没有
        Set<String> dissonance = new HashSet<>(predicted);
        dissonance.removeAll(actual);

        // 符合预期：两者共有
        Set<String> matched = new HashSet<>(actual);
        matched.retainAll(predicted);

        double totalActual = actual.size();
        double er = surprise.size() / Math.max(totalActual, 1.0); // 意外率
        double ev = dissonance.size() / Math.max(predicted.size(), 1.0); // 过拟合率
        double pressure = er - ev; // 认知压：正=意外输入, 负=过拟合

        log.info("[Prediction] 📊 认知压检测: 意外{}个, 过拟合{}个, 匹配{}个 | ER={:.2f} EV={:.2f} 压={:+.2f}",
                surprise.size(), dissonance.size(), matched.size(), er, ev, pressure);

        return new PressureReport(pressure, er, ev, surprise, dissonance, matched, lastPredictionConfidence);
    }

    // ═══════════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════════

    private static double jaccard(Set<String> a, Set<String> b) {
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0 : (double) inter.size() / union.size();
    }

    @SuppressWarnings("unchecked")
    private static List<String> fromJson(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, List.class);
        } catch (Exception e) { return List.of(); }
    }

    // ═══════════════════════════════════════════
    // 数据类
    // ═══════════════════════════════════════════

    private record BMatch(int expId, int roundNumber, double jaccard) {}

    /** 预测结果 */
    public record PredictionResult(
            Set<String> predictedTokens,
            double confidence,
            int bCount,
            int cCount
    ) {
        public static final PredictionResult EMPTY = new PredictionResult(Set.of(), 0, 0, 0);
        public boolean isEmpty() { return predictedTokens.isEmpty(); }
    }

    /** 认知压检测结果 */
    public record PressureReport(
            double pressure,        // 认知压：正=意外, 负=过拟合, 0=完全符合预期
            double surpriseRate,    // 意外率 ER
            double overfitRate,     // 过拟合率 EV
            Set<String> surpriseTokens,
            Set<String> dissonanceTokens,
            Set<String> matchedTokens,
            double confidence       // 上次预测的把握度
    ) {
        public static final PressureReport NONE = new PressureReport(0, 0, 0, Set.of(), Set.of(), Set.of(), 0);

        public String toPromptBlock() {
            if (pressure == 0 && surpriseTokens.isEmpty() && dissonanceTokens.isEmpty()) return "";

            StringBuilder sb = new StringBuilder();
            sb.append("【认知压检测 — 预期 vs 实际】\n");

            if (Math.abs(pressure) < 0.1) {
                sb.append("认知压: ~0 (预期与实际基本一致)\n");
            } else if (pressure > 0) {
                sb.append(String.format("认知压: +%.2f (意外 — 出现了预期之外的内容)\n", pressure));
                sb.append("意外token: ").append(surpriseTokens).append("\n");
            } else {
                sb.append(String.format("认知压: %.2f (过拟合 — 预期的事没有发生)\n", pressure));
                sb.append("预期落空token: ").append(dissonanceTokens).append("\n");
            }
            sb.append(String.format("把握度: %.2f, 匹配token: %s\n", confidence, matchedTokens));
            return sb.toString();
        }
    }
}
