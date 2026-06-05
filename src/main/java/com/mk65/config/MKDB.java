package com.mk65.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
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
        config.setMaximumPoolSize(1);  // SQLite 串行写最优
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

            log.info("[MKDB] SQLite 初始化完成: {}", MKConfig.DB_URL);
        } catch (SQLException e) {
            log.error("[MKDB] 数据库初始化失败", e);
            throw new RuntimeException(e);
        }
    }

    public static Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public static synchronized void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("[MKDB] 数据库连接池已关闭");
        }
    }
}
