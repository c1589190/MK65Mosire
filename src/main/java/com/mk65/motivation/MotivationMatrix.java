package com.mk65.motivation;

import com.mk65.config.MKConfig;
import com.mk65.config.MKDB;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/**
 * 共现矩阵 — MK65 动机模型核心。
 *
 * 数据结构：SQLite 表 CoMatrix(input_token, action_token, count, last_seen_round)
 * 不维护内存缓存。每次查询直读 SQLite。每次更新直写。
 */
@Slf4j
public class MotivationMatrix {

    private static volatile MotivationMatrix INSTANCE;

    private int currentRound = 0;

    private MotivationMatrix() {
        // 读取上次关停时的轮次号
        currentRound = getLatestRound() + 1;
        log.info("[MotivationMatrix] ✅ 动机模型已初始化, 起始轮次={}", currentRound);
    }

    public static MotivationMatrix getInstance() {
        if (INSTANCE == null) {
            synchronized (MotivationMatrix.class) {
                if (INSTANCE == null) {
                    INSTANCE = new MotivationMatrix();
                }
            }
        }
        return INSTANCE;
    }

    /**
     * 查询每个 token 在 CoMatrix 中的总出现次数。
     */
    public Map<String, Integer> getTokenCounts(List<String> tokens) {
        Map<String, Integer> result = new HashMap<>();
        if (tokens.isEmpty()) return result;

        String placeholders = String.join(",", Collections.nCopies(tokens.size(), "?"));
        String sql = "SELECT input_token, SUM(count) as total FROM CoMatrix WHERE input_token IN (" + placeholders + ") GROUP BY input_token";

        try (Connection conn = MKDB.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < tokens.size(); i++) {
                ps.setString(i + 1, tokens.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getString("input_token"), rs.getInt("total"));
                }
            }
        } catch (SQLException e) {
            log.error("[MotivationMatrix] 查询token总数失败", e);
        }
        return result;
    }

    // ==========================================
    // 查询：为动机报告提供数据
    // ==========================================

    /**
     * 给定输入token列表，返回每个token对应的行动分布。
     * 应用时间衰减：count 越久没出现的越贬值。
     */
    public Map<String, Map<String, Double>> queryActionDistribution(List<String> inputTokens) {
        Map<String, Map<String, Double>> result = new HashMap<>();
        if (inputTokens.isEmpty()) return result;

        String placeholders = String.join(",", Collections.nCopies(inputTokens.size(), "?"));
        String sql = "SELECT input_token, action_token, count, last_seen_round FROM CoMatrix WHERE input_token IN (" + placeholders + ")";

        try (Connection conn = MKDB.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < inputTokens.size(); i++) {
                ps.setString(i + 1, inputTokens.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String it = rs.getString("input_token");
                    String at = rs.getString("action_token");
                    int rawCount = rs.getInt("count");
                    int lastSeen = rs.getInt("last_seen_round");

                    double decayed = applyDecay(rawCount, currentRound - lastSeen);
                    if (decayed > 0) {
                        result.computeIfAbsent(it, k -> new HashMap<>())
                                .put(at, decayed);
                    }
                }
            }
        } catch (SQLException e) {
            log.error("[MotivationMatrix] 查询共现失败", e);
        }
        return result;
    }

    /**
     * 检查 token 是否新异（从未出现或累计出现次数低于阈值）。
     */
    public Set<String> findNovelTokens(List<String> inputTokens) {
        Set<String> novel = new HashSet<>();
        if (inputTokens.isEmpty()) return novel;

        String placeholders = String.join(",", Collections.nCopies(inputTokens.size(), "?"));
        String sql = "SELECT input_token, SUM(count) as total FROM CoMatrix WHERE input_token IN (" + placeholders + ") GROUP BY input_token";

        Map<String, Integer> totals = new HashMap<>();
        try (Connection conn = MKDB.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < inputTokens.size(); i++) {
                ps.setString(i + 1, inputTokens.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    totals.put(rs.getString("input_token"), rs.getInt("total"));
                }
            }
        } catch (SQLException e) {
            log.error("[MotivationMatrix] 查询新异token失败", e);
        }

        for (String token : inputTokens) {
            int total = totals.getOrDefault(token, 0);
            if (total < MKConfig.MOTIVATION_NOVELTY_MIN_COUNT) {
                novel.add(token);
            }
        }
        return novel;
    }

    // ==========================================
    // 更新：工具执行后写入
    // ==========================================

    /**
     * 每轮结束后调用。输入token和行动token的共现各+1。
     */
    public void update(List<String> inputTokens, List<String> actionTokens) {
        if (inputTokens.isEmpty() || actionTokens.isEmpty()) return;

        String sql = """
            INSERT INTO CoMatrix(input_token, action_token, count, last_seen_round)
            VALUES (?, ?, 1, ?)
            ON CONFLICT(input_token, action_token)
            DO UPDATE SET count = count + 1, last_seen_round = ?
        """;

        try (Connection conn = MKDB.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            conn.setAutoCommit(false);
            for (String it : inputTokens) {
                for (String at : actionTokens) {
                    ps.setString(1, it);
                    ps.setString(2, at);
                    ps.setInt(3, currentRound);
                    ps.setInt(4, currentRound);
                    ps.addBatch();
                }
            }
            int[] counts = ps.executeBatch();
            conn.commit();
            log.debug("[MotivationMatrix] 本轮更新: {} 条共现记录", counts.length);
        } catch (SQLException e) {
            log.error("[MotivationMatrix] 更新共现失败", e);
        }

        currentRound++;
        // 更新元状态
        saveMetaState("currentRound", String.valueOf(currentRound));
    }

    // ==========================================
    // 真空清理：定期删过期数据
    // ==========================================

    public void vacuum() {
        String sql = "DELETE FROM CoMatrix WHERE count < ? AND last_seen_round < ?";
        try (Connection conn = MKDB.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, MKConfig.MOTIVATION_VACUUM_THRESHOLD);
            ps.setInt(2, currentRound - (int) (MKConfig.MOTIVATION_DECAY_HALF_LIFE * 2));
            int deleted = ps.executeUpdate();
            if (deleted > 0) {
                log.info("[MotivationMatrix] 🧹 真空清理: {} 条过期数据已删除", deleted);
            }
        } catch (SQLException e) {
            log.error("[MotivationMatrix] 真空清理失败", e);
        }
    }

    // ==========================================
    // 辅助
    // ==========================================

    public int getCurrentRound() {
        return currentRound;
    }

    private double applyDecay(int count, int roundsSince) {
        if (roundsSince <= 0) return count;
        double decay = Math.pow(0.5, roundsSince / MKConfig.MOTIVATION_DECAY_HALF_LIFE);
        return count * decay;
    }

    private int getLatestRound() {
        return Integer.parseInt(getMetaState("currentRound", "0"));
    }

    private String getMetaState(String key, String defaultVal) {
        String sql = "SELECT value FROM MetaState WHERE key = ?";
        try (Connection conn = MKDB.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("value");
            }
        } catch (SQLException e) {
            log.warn("[MotivationMatrix] 读取MetaState失败: key={}", key);
        }
        return defaultVal;
    }

    private void saveMetaState(String key, String value) {
        String sql = "INSERT OR REPLACE INTO MetaState(key, value) VALUES (?, ?)";
        try (Connection conn = MKDB.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("[MotivationMatrix] 保存MetaState失败: key={}", key);
        }
    }
}
