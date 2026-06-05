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

    public static void main(String[] args) {
        log.info("╔══════════════════════════════════════╗");
        log.info("║       MK65Mosire Alpha 1.0.0         ║");
        log.info("║   白箱动机模型驱动的 AI Agent 框架     ║");
        log.info("╚══════════════════════════════════════╝");
        log.info("工作目录: {}", System.getProperty("user.dir"));
        log.info("数据库: {}", MKConfig.DB_URL);

        // 1. 初始化 ActionLoop（会自动初始化工具箱、LLM适配器、动机模型）
        ActionLoop loop = ActionLoop.getInstance();

        // 2. 尝试启动 NapcatQQ
        if (MKConfig.NAPCAT_TOKEN != null && !MKConfig.NAPCAT_TOKEN.isBlank()
                || MKConfig.NAPCAT_WS_URL != null && !MKConfig.NAPCAT_WS_URL.isBlank()) {
            try {
                napcat = new NapcatAdapter();
                napcat.setMessageCallback((source, text) ->
                        loop.getInputBuilder().onExternalMessage(source, text));
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
                    loop.getInputBuilder().onConsoleInput(line);
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
