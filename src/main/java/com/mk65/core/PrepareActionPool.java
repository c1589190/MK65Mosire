package com.mk65.core;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 准备行动池。
 *
 * 待处理的 ActionInput 先入池，按优先级排序后出池。
 * 当前排序：先按 priority 降序，再按创建时间升序（同优先级FIFO）。
 * 后续可接入注意力机制调整排序权重。
 */
@Slf4j
public class PrepareActionPool {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final AtomicLong sequence = new AtomicLong(0);

    private final PriorityBlockingQueue<PoolEntry> pool;
    private final ReentrantLock lock = new ReentrantLock();

    private static final int DEFAULT_CAPACITY = 64;

    public PrepareActionPool() {
        // 按 selectionScore 降序 → 同分按时序
        this.pool = new PriorityBlockingQueue<>(DEFAULT_CAPACITY,
                (a, b) -> {
                    int c = Double.compare(b.selectionScore(), a.selectionScore());
                    if (c != 0) return c;
                    return Long.compare(a.sequenceId, b.sequenceId);
                });
    }

    // ==========================================
    // 入池
    // ==========================================

    /** 来自外部适配器（QQ消息等） */
    public void pushExternal(String source, String rawText) {
        String now = LocalDateTime.now().format(TIME_FMT);
        String actionText;
        if (source.startsWith("qq_group:")) {
            actionText = String.format("[%s] QQ群聊(%s): %s", now,
                    source.substring("qq_group:".length()), rawText);
        } else if (source.startsWith("qqid:")) {
            actionText = String.format("[%s] QQ私聊(%s): %s", now,
                    source.substring("qqid:".length()), rawText);
        } else {
            actionText = String.format("[%s] 来源(%s): %s", now, source, rawText);
        }
        push(new ActionInput(actionText, source, rawText, 0.5));
    }

    /** 来自控制台 */
    public void pushConsole(String rawText) {
        String actionText = String.format("[%s] 控制台输入: %s",
                LocalDateTime.now().format(TIME_FMT), rawText);
        push(new ActionInput(actionText, "console", rawText, 0.8));
    }

    /** 来自 LLM 自生成（create_task 工具） */
    public void pushInternal(String description, double priority) {
        String actionText = String.format("[%s] 内部任务: %s",
                LocalDateTime.now().format(TIME_FMT), description);
        push(new ActionInput(actionText, "internal", description,
                Math.max(0.0, Math.min(1.0, priority))));
    }

    /** 通用入池 */
    public void push(ActionInput input) {
        if (input == null) return;
        pool.add(new PoolEntry(input, sequence.getAndIncrement()));
        log.debug("[Pool] 📥 入池: src={}, pri={}, size={}",
                input.source(), input.priority(), pool.size());
    }

    // ==========================================
    // 出池
    // ==========================================

    /** 阻塞获取 */
    public ActionInput take() throws InterruptedException {
        return pool.take().input;
    }

    /** 非阻塞获取，超时返回null */
    public ActionInput poll(long timeoutMs) throws InterruptedException {
        PoolEntry entry = pool.poll(timeoutMs, TimeUnit.MILLISECONDS);
        return entry != null ? entry.input : null;
    }

    /** 非阻塞，立即返回或null */
    public ActionInput pollNow() {
        PoolEntry entry = pool.poll();
        return entry != null ? entry.input : null;
    }

    // ==========================================
    // 查询
    // ==========================================

    public int size() { return pool.size(); }

    public boolean isEmpty() { return pool.isEmpty(); }

    /** 查看当前排序第一的项但不取出 */
    public ActionInput peek() {
        PoolEntry entry = pool.peek();
        return entry != null ? entry.input : null;
    }

    /** 清空池 */
    public void clear() {
        pool.clear();
        sequence.set(0);
    }

    // ==========================================
    // 内部
    // ==========================================

    /**
     * 池中的包装条目。
     * selectionScore 目前 = priority（后续注意力机制会调整）。
     * sequenceId 保证同分时FIFO。
     */
    private static class PoolEntry {
        final ActionInput input;
        final long sequenceId;
        final long createdAt;  // epoch millis

        PoolEntry(ActionInput input, long sequenceId) {
            this.input = input;
            this.sequenceId = sequenceId;
            this.createdAt = System.currentTimeMillis();
        }

        double selectionScore() {
            // 同 priority 时，先入池的优先（FIFO）
            // 后续在这里加权：注意力、等待时间惩罚、来源偏好等
            return input.priority();
        }
    }

    // ==========================================
    // 数据类
    // ==========================================

    public record ActionInput(
            String actionText,
            String source,
            String rawText,
            double priority,
            long createdAt
    ) {
        public ActionInput(String actionText, String source, String rawText, double priority) {
            this(actionText, source, rawText, priority, System.currentTimeMillis());
        }
    }
}
