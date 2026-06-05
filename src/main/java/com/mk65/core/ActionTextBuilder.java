package com.mk65.core;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * ActionText 构建器。
 *
 * 职责：
 * 1. 从外部适配器接收原始消息 → 拼接为 ActionText
 * 2. 从控制台接收输入 → 拼接为 ActionText（source="console"）
 * 3. 将 ActionText 放入队列供 ActionLoop 消费
 */
public class ActionTextBuilder {

    private final BlockingQueue<ActionInput> inputQueue = new LinkedBlockingQueue<>();

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 从 QQ 适配器收到的消息。
     */
    public void onExternalMessage(String source, String text) {
        String now = LocalDateTime.now().format(TIME_FMT);
        String actionText;

        if (source.startsWith("qq_group:")) {
            String groupId = source.substring("qq_group:".length());
            actionText = String.format("[%s] QQ群聊(%s)有新消息: %s", now, groupId, text);
        } else if (source.startsWith("qqid:")) {
            String userId = source.substring("qqid:".length());
            actionText = String.format("[%s] QQ私聊(%s)有新消息: %s", now, userId, text);
        } else {
            actionText = String.format("[%s] 来源(%s): %s", now, source, text);
        }

        inputQueue.add(new ActionInput(actionText, source, text));
    }

    /**
     * 从控制台收到的输入。
     */
    public void onConsoleInput(String text) {
        String now = LocalDateTime.now().format(TIME_FMT);
        String source = "console";
        String actionText = String.format("[%s] 控制台输入: %s", now, text);
        inputQueue.add(new ActionInput(actionText, source, text));
    }

    /**
     * 内部生成的任务（LLM自我驱动的 next_action）。
     */
    public void onInternalTask(String description, double priority) {
        String now = LocalDateTime.now().format(TIME_FMT);
        String actionText = String.format("[%s] 内部任务: %s", now, description);
        inputQueue.add(new ActionInput(actionText, "internal", description, priority));
    }

    /**
     * 阻塞获取下一个输入。
     */
    public ActionInput take() throws InterruptedException {
        return inputQueue.take();
    }

    /**
     * 非阻塞获取（超时返回null）。
     */
    public ActionInput poll(long timeoutMs) throws InterruptedException {
        return inputQueue.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * 输入数据类。
     */
    public record ActionInput(
            String actionText,      // 完整的ActionText（含时间戳和来源前缀）
            String source,           // 来源标识: "qq_group:xxx", "qqid:xxx", "console", "internal"
            String rawText,          // 原始文本（不含前缀）
            double priority          // 优先级（外部消息=0.5, 控制台=0.8, 内部任务可变）
    ) {
        public ActionInput(String actionText, String source, String rawText) {
            this(actionText, source, rawText,
                    "console".equals(source) ? 0.8 : 0.5);
        }
        public ActionInput(String actionText, String source, String rawText, double priority) {
            this.actionText = actionText;
            this.source = source;
            this.rawText = rawText;
            this.priority = priority;
        }
    }
}
