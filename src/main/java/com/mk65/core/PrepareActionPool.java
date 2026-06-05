package com.mk65.core;

import lombok.extern.slf4j.Slf4j;
import com.mk65.config.MKConfig;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 准备行动池。
 *
 * 选择评分 = 外源刺激 + 等待加成 + 内源加成。
 * 外源：ActionText自身的刺激量（来源权重/长度/新颖度/对立数/部署者）。
 * 等待：已在池中停留的轮数 × waitingWeight。
 * 内源：LLM通过select_next显式加成（持久，执行后清零）。
 */
@Slf4j
public class PrepareActionPool {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final AtomicLong sequence = new AtomicLong(0);
    private int currentRound = 0;

    private final PriorityBlockingQueue<PoolEntry> pool;
    private final ScheduledExecutorService flushScheduler;
    private final ConcurrentHashMap<String, MessageBucket> buckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> cooldowns = new ConcurrentHashMap<>();

    private static final int DEFAULT_CAPACITY = 64;

    // ── 外源刺激计算需要的上下文（由 ActionLoop 每轮注入）──
    // 后续用于 token级别新颖度/对立统计

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

    // ═══════════════════════════════════════════
    // 入池
    // ═══════════════════════════════════════════

    public void pushExternal(String source, String rawText) {
        Long cooldownUntil = cooldowns.get(source);
        if (cooldownUntil != null && System.currentTimeMillis() < cooldownUntil) return;

        synchronized (buckets) {
            MessageBucket bucket = buckets.computeIfAbsent(source, k -> new MessageBucket(source));
            bucket.messages.add(rawText);
            bucket.lastActivity = System.currentTimeMillis();

            int minMsgs = source.startsWith("qq_group:") ? MKConfig.MSG_AGGREGATE_GROUP_MIN : MKConfig.MSG_AGGREGATE_PRIVATE_MIN;
            int effectiveMax = Math.max(minMsgs, 1);

            if (bucket.messages.size() >= Math.max(effectiveMax, MKConfig.MSG_AGGREGATE_MAX_MESSAGES)) {
                flushBucket(source);
                return;
            }
            if (bucket.flushTask != null) bucket.flushTask.cancel(false);
            bucket.flushTask = flushScheduler.schedule(
                    () -> flushBucket(source),
                    MKConfig.MSG_AGGREGATE_WAIT_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void flushBucket(String source) {
        MessageBucket bucket;
        synchronized (buckets) {
            bucket = buckets.remove(source);
            if (bucket == null || bucket.messages.isEmpty()) return;
            cooldowns.put(source, System.currentTimeMillis() + MKConfig.MSG_AGGREGATE_COOLDOWN_MS);
        }

        String now = LocalDateTime.now().format(TIME_FMT);
        String combined = String.join("\n", bucket.messages);
        String label = source.startsWith("qq_group:") ? "QQ群聊(" + source.substring("qq_group:".length()) + ")"
                : source.startsWith("qqid:") ? "QQ私聊(" + source.substring("qqid:".length()) + ")"
                : "来源(" + source + ")";
        String actionText = String.format("[%s] %s 聚合%d条: %s", now, label, bucket.messages.size(), combined);

        double baseWeight = source.startsWith("qqid:") ? MKConfig.STIMULUS_PRIVATE_WEIGHT
                : source.startsWith("qq_group:") ? MKConfig.STIMULUS_GROUP_WEIGHT
                : 0.5;

        push(new ActionInput(actionText, source, combined, baseWeight));
    }

    public void flushAll() {
        synchronized (buckets) {
            for (String source : new ArrayList<>(buckets.keySet())) flushBucket(source);
        }
        buckets.clear();
    }

    public void pushConsole(String rawText) {
        String actionText = String.format("[%s] 控制台输入: %s", LocalDateTime.now().format(TIME_FMT), rawText);
        push(new ActionInput(actionText, "console", rawText, MKConfig.STIMULUS_CONSOLE_WEIGHT));
    }

    public void pushInternal(String description, double priority) {
        String actionText = String.format("[%s] 内部任务: %s", LocalDateTime.now().format(TIME_FMT), description);
        push(new ActionInput(actionText, "internal", description,
                Math.max(0.0, Math.min(1.0, priority)) * MKConfig.STIMULUS_INTERNAL_WEIGHT));
    }

    public void push(ActionInput input) {
        if (input == null) return;
        pool.add(new PoolEntry(input, sequence.getAndIncrement(), currentRound));
    }

    // ═══════════════════════════════════════════
    // 出池
    // ═══════════════════════════════════════════

    public ActionInput take() throws InterruptedException {
        PoolEntry e = pool.take();
        e.endogenousBoost = 0; // 执行后清零内源加成
        return e.input;
    }

    public ActionInput poll(long timeoutMs) throws InterruptedException {
        PoolEntry e = pool.poll(timeoutMs, TimeUnit.MILLISECONDS);
        if (e != null) e.endogenousBoost = 0;
        return e != null ? e.input : null;
    }

    // ═══════════════════════════════════════════
    // 内源加成
    // ═══════════════════════════════════════════

    public void applyEndogenousBoost(int index) {
        List<PoolEntry> entries = getAllEntries();
        if (index < 0 || index >= entries.size() || entries.size() < 2) return;

        PoolEntry target = entries.get(index);
        PoolEntry first = entries.get(0);

        if (target == first) return; // 已经是第一

        double margin = target.selectionScore() + MKConfig.POOL_ENDOGENOUS_MARGIN - first.selectionScore();
        target.endogenousBoost += margin;
        log.info("[Pool] 💡 内源加成: [{}] +{:.3f} → 得分={:.3f} (已升至第一)",
                index, margin, target.selectionScore());
    }

    // ═══════════════════════════════════════════
    // 查询：给LLM看的行动列表
    // ═══════════════════════════════════════════

    public List<ActionSummary> getActionList() {
        List<PoolEntry> entries = getAllEntries();
        List<ActionSummary> list = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            PoolEntry e = entries.get(i);
            ActionInput in = e.input;

            double exogenous = computeStimulus(e); // 重算外源（当前轮可能有新token信息）
            double waiting = (currentRound - e.roundCreated) * MKConfig.POOL_WAITING_WEIGHT;

            list.add(new ActionSummary(
                    i, e.uuid,
                    in.source(), in.rawText().length() > 80 ? in.rawText().substring(0, 80) + "..." : in.rawText(),
                    e.selectionScore(), exogenous, waiting, e.endogenousBoost,
                    0, "无",  // novelTokens/oppositions: 后续细化
                    currentRound - e.roundCreated
            ));
        }
        return list;
    }

    /** 更新当前轮次（ActionLoop每轮调用） */
    public void setRound(int round) {
        this.currentRound = round;
    }

    // ═══════════════════════════════════════════
    // 查询基础
    // ═══════════════════════════════════════════

    public int size() { return pool.size(); }
    public boolean isEmpty() { return pool.isEmpty(); }
    public ActionInput peek() { PoolEntry e = pool.peek(); return e != null ? e.input : null; }

    public void clear() {
        pool.clear(); buckets.clear(); cooldowns.clear(); sequence.set(0);
    }
    public void shutdown() {
        flushAll(); flushScheduler.shutdown(); pool.clear();
    }

    // ═══════════════════════════════════════════
    // 内部
    // ═══════════════════════════════════════════

    private List<PoolEntry> getAllEntries() {
        List<PoolEntry> list = new ArrayList<>(pool);
        list.sort((a, b) -> {
            int c = Double.compare(b.selectionScore(), a.selectionScore());
            if (c != 0) return c;
            return Long.compare(a.sequenceId, b.sequenceId);
        });
        return list;
    }

    private double computeStimulus(PoolEntry e) {
        ActionInput in = e.input;
        double base = in.priority(); // 入池时的基础权重

        // 长度加成
        int tokenEstimate = in.rawText().length() / 2; // 粗略估计token数
        double lengthBonus = Math.min(0.2, tokenEstimate / (double) MKConfig.STIMULUS_LENGTH_DIVISOR * 0.01);

        return base + lengthBonus;
    }

    private static class PoolEntry {
        final UUID uuid = UUID.randomUUID();
        final ActionInput input;
        final long sequenceId;
        final int roundCreated;
        double endogenousBoost = 0;

        PoolEntry(ActionInput input, long sequenceId, int roundCreated) {
            this.input = input;
            this.sequenceId = sequenceId;
            this.roundCreated = roundCreated;
        }

        double selectionScore() {
            // 外源在getActionList时实时计算，这里用入池时的基础值做排序
            return input.priority() + endogenousBoost;
        }
    }

    private static class MessageBucket {
        final String source;
        final List<String> messages = new ArrayList<>();
        long lastActivity;
        ScheduledFuture<?> flushTask;
        MessageBucket(String source) { this.source = source; this.lastActivity = System.currentTimeMillis(); }
    }

    // ═══════════════════════════════════════════
    // 数据类
    // ═══════════════════════════════════════════

    public record ActionInput(
            String actionText, String source, String rawText, double priority, long createdAt
    ) {
        public ActionInput(String actionText, String source, String rawText, double priority) {
            this(actionText, source, rawText, priority, System.currentTimeMillis());
        }
    }

    public record ActionSummary(
            int index, UUID uuid,
            String source, String preview,
            double score, double exogenous, double waiting, double endogenous,
            int novelTokens, String oppositions,
            int waitingRounds
    ) {}
}
