package com.mk65;

import com.mk65.adapter.NapcatAdapter;
import com.mk65.config.MKConfig;
import com.mk65.core.ActionLoop;
import com.mk65.tool.SendMessage;
import lombok.extern.slf4j.Slf4j;

import java.net.URISyntaxException;
import java.util.Scanner;

/**
 * MK65Mosire 入口。
 *
 * 启动顺序：
 * 1. 加载配置
 * 2. 初始化动机模型（SQLite）
 * 3. 启动 NapcatQQ 适配器（如果配置了）
 * 4. 启动 ActionLoop
 * 5. 打开控制台交互
 */
@Slf4j
public class Main {

    private static volatile boolean running = true;
    private static NapcatAdapter napcat;

    /**
     * 首次运行时，如果当前目录没有 application.properties，则生成模板。
     */
    private static void ensureConfigTemplate() {
        java.nio.file.Path configPath = java.nio.file.Path.of(CONFIG_FILE);
        if (java.nio.file.Files.exists(configPath)) return;

        String template = """
            # ==========================================
            # MK65Mosire 配置文件
            # 由首次运行自动生成，请填入你的配置后重新启动
            # ==========================================

            # ---------- LLM 大脑模型 ----------
            llm.brain.apiBase=http://127.0.0.1:3000/v1
            llm.brain.apiKey=
            llm.brain.chatModel=deepseek-v4-flash-max
            llm.brain.temperature=0.6
            llm.brain.maxTokens=65535
            llm.brain.stream=true

            # ---------- NapcatQQ ----------
            napcat.wsUrl=127.0.0.1
            napcat.wsPort=3001
            napcat.httpUrl=http://127.0.0.1:3000
            napcat.token=

            # ---------- 搜索 ----------
            search.braveApiKey=
            search.metasoApiKey=

            # ---------- 工作区 ----------
            workspace.dir=workspace

            # ---------- 动机模型 ----------
            motivation.conflictThreshold=0.5
            motivation.noveltyMinCount=3
            motivation.decayHalfLife=500
            motivation.vacuumThreshold=1

            # ---------- 认知循环 ----------
            core.tickMs=2000
            core.roundTimeoutSec=180

            # ---------- 数据库 ----------
            db.url=jdbc:sqlite:mk65_motivation.db
            """;

        try {
            java.nio.file.Files.writeString(configPath, template);
            System.out.println();
            System.out.println("📄 已生成配置模板: " + configPath.toAbsolutePath());
            System.out.println("   请编辑此文件，至少填入 llm.brain.apiKey 后重新启动。");
            System.out.println();
        } catch (Exception e) {
            System.err.println("❌ 无法生成配置文件: " + e.getMessage());
        }
    }

    private static final String CONFIG_FILE = "application.properties";

    public static void main(String[] args) {
        // 0. 首次运行：生成配置模板
        ensureConfigTemplate();

        log.info("╔══════════════════════════════════════╗");
        log.info("║       MK65Mosire Alpha 1.0.0         ║");
        log.info("║   白箱动机模型驱动的 AI Agent 框架     ║");
        log.info("╚══════════════════════════════════════╝");
        log.info("工作目录: {}", System.getProperty("user.dir"));

        // 0.5. 检查是否已配置
        if (MKConfig.BRAIN_API_KEY == null || MKConfig.BRAIN_API_KEY.isBlank()) {
            System.out.println();
            System.out.println("⚠️  llm.brain.apiKey 未配置！");
            System.out.println("   请编辑当前目录下的 " + CONFIG_FILE + "，填入你的 API Key 后重新启动。");
            System.out.println();
            System.exit(1);
        }

        log.info("数据库: {}", MKConfig.DB_URL);

        // 1. 初始化 ActionLoop（会自动初始化工具箱、LLM适配器、动机模型）
        ActionLoop loop = ActionLoop.getInstance();

        // 2. 尝试启动 NapcatQQ
        if (MKConfig.NAPCAT_TOKEN != null && !MKConfig.NAPCAT_TOKEN.isBlank()
                || MKConfig.NAPCAT_WS_URL != null && !MKConfig.NAPCAT_WS_URL.isBlank()) {
            try {
                napcat = new NapcatAdapter();
                napcat.setMessageCallback((source, text) ->
                        loop.getActionPool().pushExternal(source, text));
                napcat.start();
                SendMessage.setNapcat(napcat);
                log.info("[Main] NapcatQQ 适配器已启动");
            } catch (URISyntaxException e) {
                log.error("[Main] NapcatQQ URL 配置错误，将以纯控制台模式运行", e);
            } catch (Exception e) {
                log.error("[Main] NapcatQQ 启动失败，将以纯控制台模式运行: {}", e.getMessage());
            }
        } else {
            log.info("[Main] 未配置 NapcatQQ，纯控制台模式");
        }

        // 3. 设置控制台消息回调
        SendMessage.setConsoleSender(msg -> {
            System.out.println("\n┌── Console Output ─────────────────────");
            System.out.println(msg);
            System.out.println("└───────────────────────────────────────");
        });

        // 4. 启动认知循环
        loop.start();

        // 5. 控制台交互
        log.info("[Main] 控制台已就绪。输入消息后回车，或输入 /quit 退出。");
        System.out.println("\n══════════════ MK65Mosire Console ══════════════");
        System.out.println("输入消息后回车，或输入 /quit 退出，/stats 查看统计");
        System.out.println("═════════════════════════════════════════════════\n");

        try (Scanner scanner = new Scanner(System.in)) {
            while (running) {
                System.out.print("> ");
                String line = scanner.nextLine();
                if (line.isBlank()) continue;

                if ("/quit".equalsIgnoreCase(line.trim())) {
                    running = false;
                } else if ("/stats".equalsIgnoreCase(line.trim())) {
                    printStats(loop);
                } else {
                    loop.getActionPool().pushConsole(line);
                }
            }
        }

        // 6. 关闭
        shutdown(loop);
    }

    private static void printStats(ActionLoop loop) {
        int round = loop.getRoundCount();
        int tools = loop.getToolbox().size();
        var matrix = com.mk65.motivation.MotivationMatrix.getInstance();
        int currentMatrixRound = matrix.getCurrentRound();

        System.out.println("\n┌── MK65 Stats ─────────────────────────");
        System.out.println("  累计轮次: " + round);
        System.out.println("  动机模型轮次: " + currentMatrixRound);
        System.out.println("  已注册工具: " + tools);
        System.out.println("  QQ 适配器: " + (napcat != null && napcat.isConnected() ? "已连接" : "未连接"));
        System.out.println("  数据库: " + MKConfig.DB_URL);
        System.out.println("└───────────────────────────────────────\n");
    }

    private static void shutdown(ActionLoop loop) {
        log.info("[Main] 正在关闭...");
        running = false;

        if (napcat != null) {
            napcat.stop();
        }
        loop.stop();
        log.info("[Main] 已完成关闭。再见。");
        System.exit(0);
    }
}
