package com.mk65.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * MK65 数据库 — SQLite 单文件嵌入式。
 * 存共现矩阵和元状态。查询走索引，无内存缓存。
 */
@Slf4j
public class MKDB {

    private static volatile HikariDataSource dataSource;

    static {
        init();
    }

    public static synchronized void init() {
        if (dataSource != null && !dataSource.isClosed()) return;

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(MKConfig.DB_URL);
        config.setMaximumPoolSize(1);
        config.setConnectionTimeout(5000);
        dataSource = new HikariDataSource(config);

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS CoMatrix (
                    input_token  TEXT NOT NULL,
                    action_token TEXT NOT NULL,
                    count        INTEGER NOT NULL DEFAULT 1,
                    last_seen_round INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (input_token, action_token)
                )
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_cm_input ON CoMatrix(input_token)");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS MetaState (
                    key   TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
            """);

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS Experiences (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    round_number    INTEGER NOT NULL,
                    action_text     TEXT NOT NULL,
                    source          TEXT NOT NULL,
                    thoughts        TEXT,
                    tool_names      TEXT NOT NULL DEFAULT '[]',
                    tool_results    TEXT NOT NULL DEFAULT '[]',
                    input_tokens    TEXT NOT NULL DEFAULT '[]',
                    action_tokens   TEXT NOT NULL DEFAULT '[]',
                    helpful_count   INTEGER NOT NULL DEFAULT 0,
                    recall_count    INTEGER NOT NULL DEFAULT 0,
                    predecessor_ids TEXT NOT NULL DEFAULT '[]',
                    resolved_oppositions TEXT NOT NULL DEFAULT '[]',
                    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_exp_source ON Experiences(source)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_exp_tokens ON Experiences(input_tokens)");

            // ★ 用同一个连接做迁移（避免连接池死锁）
            ensureColumn(conn, "Experiences", "predecessor_ids", "TEXT DEFAULT '[]'");
            ensureColumn(conn, "Experiences", "resolved_oppositions", "TEXT DEFAULT '[]'");

            log.info("[MKDB] SQLite 初始化完成: {}", MKConfig.DB_URL);
        } catch (SQLException e) {
            log.error("[MKDB] 数据库初始化失败", e);
            throw new RuntimeException(e);
        }
    }

    public static Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /** 迁移辅助：如果列不存在则添加。传入已有连接避免池死锁。 */
    private static void ensureColumn(Connection conn, String table, String column, String definition) {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equals(rs.getString("name"))) return;
            }
        } catch (SQLException e) {
            log.warn("[MKDB] PRAGMA table_info 失败: {}", e.getMessage());
            return;
        }
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            log.info("[MKDB] 数据库迁移: {}.{} 列已添加", table, column);
        } catch (SQLException e) {
            log.warn("[MKDB] ALTER TABLE 失败 {}.{}: {}", table, column, e.getMessage());
        }
    }

    public static synchronized void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("[MKDB] 数据库连接池已关闭");
        }
    }
}
