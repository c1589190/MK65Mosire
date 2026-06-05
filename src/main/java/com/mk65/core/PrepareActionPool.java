package com.mk65.core;

import lombok.extern.slf4j.Slf4j;

import com.mk65.config.MKConfig;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

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
    private final ScheduledExecutorService flushScheduler;
    // 消息聚合桶: source → bucket
    private final ConcurrentHashMap<String, MessageBucket> buckets = new ConcurrentHashMap<>();
    // 冷却中的source: source → cooldown结束时间
    private final ConcurrentHashMap<String, Long> cooldowns = new ConcurrentHashMap<>();

    private static final int DEFAULT_CAPACITY = 64;

    public PrepareActionPool() {
        this.pool = new PriorityBlockingQueue<>(DEFAULT_CAPACITY,
                (a, b) -> {
                    int c = Double.compare(b.selectionScore(), a.selectionScore());
                    if (c != 0) return c;
                    return Long.compare(a.sequenceId, b.sequenceId);
                });
        this.flushScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Pool-Flusher");
            t.setDaemon(true);
            return t;
        });
    }

    // ==========================================
    // 入池
    // ==========================================

    /** 来自外部适配器（QQ消息等）— 自动聚合同源消息 */
    public void pushExternal(String source, String rawText) {
        // 检查冷却
        Long cooldownUntil = cooldowns.get(source);
        if (cooldownUntil != null && System.currentTimeMillis() < cooldownUntil) {
            // 冷却中，丢弃（或加入下一个桶）
            return;
        }

        synchronized (buckets) {
            MessageBucket bucket = buckets.computeIfAbsent(source, k -> new MessageBucket(source));

            bucket.messages.add(rawText);
            bucket.lastActivity = System.currentTimeMillis();

            int maxMsgs = source.startsWith("qq_group:") ? MKConfig.MSG_AGGREGATE_GROUP_MIN : MKConfig.MSG_AGGREGATE_PRIVATE_MIN;
            int effectiveMax = Math.max(maxMsgs, 1);

            // 攒够了→立即flush
            if (bucket.messages.size() >= Math.max(effectiveMax, MKConfig.MSG_AGGREGATE_MAX_MESSAGES)) {
                flushBucket(source);
                return;
            }

            // 取消旧定时器，重新计时
            if (bucket.flushTask != null) bucket.flushTask.cancel(false);
            bucket.flushTask = flushScheduler.schedule(
                    () -> flushBucket(source),
                    MKConfig.MSG_AGGREGATE_WAIT_MS, TimeUnit.MILLISECONDS);
        }
    }

    /** 清空聚合桶，拼接所有消息为一条ActionInput推入池 */
    private void flushBucket(String source) {
        MessageBucket bucket;
        synchronized (buckets) {
            bucket = buckets.remove(source);
            if (bucket == null || bucket.messages.isEmpty()) return;
            // 设置冷却
            cooldowns.put(source, System.currentTimeMillis() + MKConfig.MSG_AGGREGATE_COOLDOWN_MS);
        }

        String now = LocalDateTime.now().format(TIME_FMT);
        String combined = String.join("\n", bucket.messages);
        String actionText;
        if (source.startsWith("qq_group:")) {
            actionText = String.format("[%s] QQ群聊(%s) 聚合%d条: %s", now,
                    source.substring("qq_group:".length()), bucket.messages.size(), combined);
        } else if (source.startsWith("qqid:")) {
            actionText = String.format("[%s] QQ私聊(%s) 聚合%d条: %s", now,
                    source.substring("qqid:".length()), bucket.messages.size(), combined);
        } else {
            actionText = String.format("[%s] 来源(%s) 聚合%d条: %s", now, source, bucket.messages.size(), combined);
        }

        double pri = source.startsWith("qqid:") ? 0.7 : 0.5;
        push(new ActionInput(actionText, source, combined, pri));
        log.info("[Pool] 📦 消息聚合flush: source={}, {}条消息→{}chars ActionText, pri={}",
                source, bucket.messages.size(), actionText.length(), pri);
    }

    /** 关闭时清空所有桶 */
    public void flushAll() {
        synchronized (buckets) {
            for (String source : new ArrayList<>(buckets.keySet())) {
                flushBucket(source);
            }
        }
        buckets.clear();
    }

    private static class MessageBucket {
        final String source;
        final List<String> messages = new ArrayList<>();
        long lastActivity;
        ScheduledFuture<?> flushTask;

        MessageBucket(String source) {
            this.source = source;
            this.lastActivity = System.currentTimeMillis();
        }
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
        buckets.clear();
        cooldowns.clear();
        sequence.set(0);
    }

    public void shutdown() {
        flushAll();
        flushScheduler.shutdown();
        pool.clear();
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
