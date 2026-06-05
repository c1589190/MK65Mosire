package com.mk65.motivation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mk65.config.MKDB;
import lombok.extern.slf4j.Slf4j;

import java.sql.*;
import java.util.*;

/**
 * 经验管理器 — 自动录制 + Jaccard检索。
 *
 * 存储：每轮结束后自动录制一条经验（输入tokens、行动tokens、工具名、结果等）。
 * 检索：用Jaccard系数匹配当前输入tokens和历史经验的input_tokens，找最相似的场景。
 * 不需要LLM主动存——实践自然留痕。
 */
@Slf4j
public class MemoryManager {

    private static volatile MemoryManager INSTANCE;
    private static final ObjectMapper mapper = new ObjectMapper();

    private MemoryManager() {}

    public static MemoryManager getInstance() {
        if (INSTANCE == null) {
            synchronized (MemoryManager.class) {
                if (INSTANCE == null) INSTANCE = new MemoryManager();
            }
        }
        return INSTANCE;
    }

    // ==========================================
    // 自动录制
    // ==========================================

    /**
     * 每轮结束后调用。录入经验。
     */
    public int record(int roundNumber, String actionText, String source,
                       String thoughts, List<String> toolNames, List<String> toolResults,
                       List<String> inputTokens, List<String> actionTokens) {
        String sql = """
            INSERT INTO Experiences
            (round_number, action_text, source, thoughts, tool_names, tool_results, input_tokens, action_tokens)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;

        try (Connection conn = MKDB.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, roundNumber);
            ps.setString(2, truncate(actionText, 2000));
            ps.setString(3, source);
            ps.setString(4, truncate(thoughts, 1000));
            ps.setString(5, toJson(toolNames));
            ps.setString(6, toJson(toolResults));
            ps.setString(7, toJson(inputTokens));
            ps.setString(8, toJson(actionTokens));
            ps.executeUpdate();

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT last_insert_rowid()")) {
                if (rs.next()) return rs.getInt(1);
            }
        } catch (SQLException e) {
            log.error("[MemoryManager] 经验录制失败", e);
        }
        return -1;
    }

    // ==========================================
    // Jaccard 自动检索
    // ==========================================

    /**
     * 用当前输入的 token 集合，与历史经验做 Jaccard 相似度匹配。
     * 返回 top N 条最相关经验。
     */
    public List<ExpMatch> autoRecall(List<String> currentTokens, int topN) {
        if (currentTokens == null || currentTokens.isEmpty()) return List.of();
        Set<String> currentSet = new HashSet<>(currentTokens);

        String sql = "SELECT id, round_number, action_text, source, thoughts, tool_names, tool_results, input_tokens, helpful_count FROM Experiences ORDER BY id DESC LIMIT 500";

        List<ExpMatch> matches = new ArrayList<>();

        try (Connection conn = MKDB.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                List<String> expTokens = fromJsonArray(rs.getString("input_tokens"));
                if (expTokens.isEmpty()) continue;

                Set<String> expSet = new HashSet<>(expTokens);
                double jaccard = jaccard(currentSet, expSet);
                if (jaccard <= 0) continue;

                int helpful = rs.getInt("helpful_count");

                matches.add(new ExpMatch(
                        rs.getInt("id"),
                        rs.getInt("round_number"),
                        rs.getString("action_text"),
                        rs.getString("source"),
                        rs.getString("thoughts"),
                        fromJsonArray(rs.getString("tool_names")),
                        fromJsonArray(rs.getString("tool_results")),
                        jaccard * (1.0 + Math.tanh(helpful * 0.5))  // 有帮助的经验加权
                ));
            }
        } catch (SQLException e) {
            log.error("[MemoryManager] 经验检索失败", e);
        }

        matches.sort((a, b) -> Double.compare(b.jaccard, a.jaccard));
        if (matches.size() > topN) matches = matches.subList(0, topN);

        // 更新命中计数
        for (ExpMatch m : matches) {
            incrementRecallCount(m.id);
        }

        return matches;
    }

    // ==========================================
    // 关键词搜索（recall 工具调用）
    // ==========================================

    /**
     * LLM 主动调用 recall 时，用关键词匹配经验库中的 action_text 和 input_tokens。
     */
    public List<ExpMatch> searchByKeywords(String keywords, int limit) {
        if (keywords == null || keywords.isBlank()) return List.of();

        String[] parts = keywords.toLowerCase().split("[\\s,，、]+");
        StringBuilder where = new StringBuilder();
        List<String> params = new ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) where.append(" OR ");
            where.append("action_text LIKE ? OR input_tokens LIKE ?");
            String p = "%" + parts[i] + "%";
            params.add(p);
            params.add(p);
        }

        String sql = "SELECT id, round_number, action_text, source, thoughts, tool_names, tool_results, input_tokens FROM Experiences WHERE " + where + " ORDER BY id DESC LIMIT " + limit;

        List<ExpMatch> matches = new ArrayList<>();
        try (Connection conn = MKDB.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    matches.add(new ExpMatch(
                            rs.getInt("id"),
                            rs.getInt("round_number"),
                            rs.getString("action_text"),
                            rs.getString("source"),
                            rs.getString("thoughts"),
                            fromJsonArray(rs.getString("tool_names")),
                            fromJsonArray(rs.getString("tool_results")),
                            0  // 关键词搜索不设Jaccard值
                    ));
                }
            }
        } catch (SQLException e) {
            log.error("[MemoryManager] 关键词搜索失败", e);
        }

        // 更新命中计数
        for (ExpMatch m : matches) {
            incrementRecallCount(m.id);
        }

        // 按召回次数降序（常用经验排前面）
        return matches;
    }

    // ==========================================
    // 辅助
    // ==========================================

    private static double jaccard(Set<String> a, Set<String> b) {
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }

    /**
     * 应用经验打分。LLM 通过 finish_action 评价后调用。
     * score=1 → helpful_count+1（未来加权更高）
     * score=-1 → helpful_count-1（未来加权更低）
     */
    public void applyScore(int expId, int score) {
        if (score == 0) return;
        String sql = "UPDATE Experiences SET helpful_count = helpful_count + ? WHERE id = ?";
        try (Connection conn = MKDB.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, score);
            ps.setInt(2, expId);
            int updated = ps.executeUpdate();
            if (updated > 0) {
                log.debug("[MemoryManager] 经验#{} 打分: {:+d}", expId, score);
            }
        } catch (SQLException e) {
            log.warn("[MemoryManager] 经验打分失败 id={}", expId, e);
        }
    }

    private void incrementRecallCount(int expId) {
        String sql = "UPDATE Experiences SET recall_count = recall_count + 1 WHERE id = ?";
        try (Connection conn = MKDB.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, expId);
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    private static String toJson(Object obj) {
        try { return mapper.writeValueAsString(obj); } catch (JsonProcessingException e) { return "[]"; }
    }

    private static List<String> fromJsonArray(String json) {
        try {
            return mapper.readValue(json, mapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ==========================================
    // 数据类
    // ==========================================

    public record ExpMatch(
            int id,
            int roundNumber,
            String actionText,
            String source,
            String thoughts,
            List<String> toolNames,
            List<String> toolResults,
            double jaccard
    ) {
        public String toPromptLine() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("  [经验#%d 第%d轮] %s\n", id, roundNumber,
                    actionText.length() > 80 ? actionText.substring(0, 80) + "..." : actionText));
            if (thoughts != null && !thoughts.isBlank()) {
                sb.append(String.format("    → LLM推理: %s\n",
                        thoughts.length() > 100 ? thoughts.substring(0, 100) + "..." : thoughts));
            }
            if (!toolNames.isEmpty()) {
                sb.append("    → 工具: ").append(String.join(", ", toolNames)).append("\n");
            }
            if (!toolResults.isEmpty()) {
                String firstResult = toolResults.get(0);
                if (firstResult.length() > 100) firstResult = firstResult.substring(0, 100) + "...";
                sb.append("    → 结果: ").append(firstResult).append("\n");
            }
            return sb.toString();
        }
    }
}
