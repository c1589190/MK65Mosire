package com.mk65.core;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Logback 环形缓冲区 Appender。
 * 挂在 logback.xml 中，自动捕获所有日志事件，供 get_system_log 工具查询。
 * 无需任何手动埋点 — 现有 log.error/warn/info 全部自动收录。
 */
public class RingBufferAppender extends AppenderBase<ILoggingEvent> {

    private static final int DEFAULT_CAPACITY = 500;
    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final List<Entry> buffer;
    private int capacity;
    private int writeIndex;

    private static volatile RingBufferAppender INSTANCE;

    public RingBufferAppender() {
        this.capacity = DEFAULT_CAPACITY;
        this.buffer = new ArrayList<>(Collections.nCopies(DEFAULT_CAPACITY, null));
        this.writeIndex = 0;
        INSTANCE = this;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    public static RingBufferAppender getInstance() {
        return INSTANCE;
    }

    @Override
    protected void append(ILoggingEvent event) {
        Entry entry = new Entry(
                Instant.ofEpochMilli(event.getTimeStamp()),
                event.getLevel(),
                event.getLoggerName(),
                event.getFormattedMessage()
        );
        synchronized (buffer) {
            buffer.set(writeIndex % capacity, entry);
            writeIndex++;
        }
    }

    /** 查询日志，支持按级别和关键词筛选 */
    public List<Entry> query(Level minLevel, String keyword, int count) {
        List<Entry> all = new ArrayList<>();
        synchronized (buffer) {
            int total = Math.min(writeIndex, capacity);
            for (int i = writeIndex - total; i < writeIndex; i++) {
                Entry e = buffer.get(i % capacity);
                if (e == null) continue;
                if (!e.level.isGreaterOrEqual(minLevel)) continue;
                if (keyword != null && !keyword.isBlank()) {
                    String lower = keyword.toLowerCase();
                    if (!e.loggerName.toLowerCase().contains(lower)
                            && !e.message.toLowerCase().contains(lower)) {
                        continue;
                    }
                }
                all.add(e);
            }
        }
        int from = Math.max(0, all.size() - count);
        return all.subList(from, all.size());
    }

    public int size() { return Math.min(writeIndex, capacity); }

    // ── 数据类 ──
    public record Entry(Instant time, Level level, String loggerName, String message) {
        public String toLine() {
            // 缩短 logger 名: com.mk65.llm.LLMAdapter → LLMAdapter
            String shortName = loggerName;
            int lastDot = loggerName.lastIndexOf('.');
            if (lastDot >= 0) shortName = loggerName.substring(lastDot + 1);
            return String.format("[%s] %-5s [%s] %s",
                    FMT.format(time), level, shortName, message);
        }
    }
}
