package com.mk65.config;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * MK65Mosire 全局配置。
 * 从 classpath 和当前工作目录的 application.properties 加载。
 */
public class MKConfig {

    // ========== LLM ==========
    public static String BRAIN_API_BASE = "http://127.0.0.1:3000/v1";
    public static String BRAIN_API_KEY = "";
    public static String BRAIN_CHAT_MODEL = "deepseek-v4-flash-max";
    public static double BRAIN_TEMPERATURE = 0.6;
    public static int BRAIN_MAX_TOKENS = 65535;
    public static boolean BRAIN_STREAM = true;
    /** 是否启用思维链 (DeepSeek thinking/reasoning) */
    public static boolean BRAIN_THINKING_ENABLED = false;
    /** 思维链预算 token 数 (仅在启用时生效, 0=不限制) */
    public static int BRAIN_THINKING_BUDGET_TOKENS = 2048;

    // ========== 视觉识别 ==========
    public static boolean VISION_ENABLED = true;
    public static String VISION_API_BASE = "";
    public static String VISION_API_KEY = "";
    public static String VISION_MODEL = "";
    public static int VISION_MAX_TOKENS = 200;
    public static int VISION_TIMEOUT_SEC = 15;
    public static int VISION_MAX_IMAGES = 6;

    // ========== NapcatQQ ==========
    public static String NAPCAT_WS_URL = "127.0.0.1";
    public static int NAPCAT_WS_PORT = 3001;
    public static String NAPCAT_HTTP_URL = "http://127.0.0.1:3000";
    public static String NAPCAT_TOKEN = "";

    // ========== 搜索 ==========
    public static String SEARCH_BRAVE_API_KEY = "";
    public static String SEARCH_METASO_API_KEY = "";

    // ========== 工作区 ==========
    public static String WORKSPACE_DIR = "workspace";

    // ========== 动机模型 ==========
    public static double MOTIVATION_CONFLICT_THRESHOLD = 0.5;
    public static int MOTIVATION_NOVELTY_MIN_COUNT = 3;
    public static double MOTIVATION_DECAY_HALF_LIFE = 500.0;
    public static int MOTIVATION_VACUUM_THRESHOLD = 1;
    /** 动机报告中最多展示多少条关联历史经验 */
    public static int MOTIVATION_REPORT_MAX_EXPERIENCES = 5;
    /** 冲突对最多保留/注入多少个，超出部分自然消解 */
    public static int MOTIVATION_CONFLICT_MAX_PAIRS = 50;
    /** 冲突token在CoMatrix中最少总出现次数，低于此值的碎片token不参与冲突检测 */
    public static int MOTIVATION_CONFLICT_MIN_TOKEN_COUNT = 5;
    /** 共现扫描最近多少条经验 */
    public static int MOTIVATION_CONFLICT_COOCCUR_SCAN_LIMIT = 300;
    /** 冲突方案文本在prompt中的最大总字符数 */
    public static int MOTIVATION_CONFLICT_MAX_RESOLUTION_CHARS = 8000;

    // ========== 认知循环 ==========
    public static long CORE_TICK_MS = 2000;
    public static int CORE_ROUND_TIMEOUT_SEC = 180;

    // ========== 全局上下文 ==========
    public static int LLM_CONTEXT_MAX_MESSAGES = 42;
    public static double LLM_CONTEXT_KEEP_RATIO = 0.30;
    public static int LLM_CONTEXT_DIGEST_MAX_CHARS = 3000;

    // ========== 全局缓存重置阈值 ==========
    public static int LLM_CACHE_MAX_ENTRIES = 40;
    public static int LLM_CACHE_MAX_SIZE_CHARS = 200_000;

    // ========== 经验系统 ==========
    public static int MEMORY_AUTO_RECALL_TOPN = 3;
    public static int MEMORY_RECALL_MAX_RESULTS = 10;
    public static int MEMORY_AUTO_RECALL_SCAN_LIMIT = 500;
    public static double MEMORY_HELPFUL_SCALE = 0.5;
    public static double OPPOSITION_DISPLAY_THRESHOLD = 0.5;   // 冲突度多高才在报告中显示
    public static double EQUIVALENT_TOKEN_THRESHOLD = 0.6;      // coMatrix行向量相似度多高视为等价token

    // ========== 消息聚合 ==========
    public static long MSG_AGGREGATE_WAIT_MS = 5000;
    public static long MSG_AGGREGATE_COOLDOWN_MS = 5000;
    public static int MSG_AGGREGATE_MAX_MESSAGES = 5;
    public static int MSG_AGGREGATE_PRIVATE_MIN = 1;
    public static int MSG_AGGREGATE_GROUP_MIN = 3;
    public static int MSG_AGGREGATE_CHAR_FLUSH = 200;       // 总字符数超过此值立即flush
    public static double MSG_AGGREGATE_MS_PER_CHAR = 20;    // 每个字符减少的等待毫秒
    public static int MSG_AGGREGATE_MIN_WAIT_MS = 500;      // 最低等待毫秒（防0等待）

    // ========== 外源刺激 ==========
    public static double STIMULUS_PRIVATE_WEIGHT = 0.7;
    public static double STIMULUS_GROUP_WEIGHT = 0.5;
    public static double STIMULUS_CONSOLE_WEIGHT = 0.8;
    public static double STIMULUS_INTERNAL_WEIGHT = 0.3;
    public static int STIMULUS_LENGTH_DIVISOR = 20;

    // ========== 行动池权重 ==========
    public static double POOL_WAITING_WEIGHT = 0.01;
    public static double POOL_ENDOGENOUS_MARGIN = 0.01;
    /** LLM处理期间，同源消息累加的最大字符数 (超出从顶部裁剪) */
    public static int POOL_PROCESSING_MAX_CHARS = 3000;

    // ========== 聊天历史 ==========
    public static int CHAT_HISTORY_AUTO_COUNT = 10;
    public static int CHAT_HISTORY_TOOL_COUNT = 48;

    // ========== 数据库 ==========
    public static String DB_URL = "jdbc:sqlite:mk65_motivation.db";
    public static boolean DEBUG_AUTO_ENABLE = false;

    // ========== 配置管理 ==========
    /** 启动时自动写出完整配置文件 application.properties.full */
    public static boolean CONFIG_AUTO_COMPLETE = true;

    // ★ 配置来源追踪: key → "文件" | "环境" | "默认"
    private static final java.util.Map<String, String> CONFIG_SOURCES = new java.util.LinkedHashMap<>();

    static {
        Properties props = new Properties();

        // 1. classpath 默认配置
        try (InputStream is = MKConfig.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (is != null) props.load(is);
        } catch (Exception ignored) {}

        // 2. 工作目录覆盖
        Path cwdFile = Path.of("application.properties");
        if (Files.exists(cwdFile)) {
            try (FileInputStream fis = new FileInputStream(cwdFile.toFile())) {
                Properties overlay = new Properties();
                overlay.load(fis);
                props.putAll(overlay);
            } catch (Exception ignored) {}
        }

        // ★ 记录文件来源的 key
        for (String key : props.stringPropertyNames()) {
            CONFIG_SOURCES.put(key, "文件");
        }

        // 3. 环境变量覆盖（SILICONFLOW_API_KEY / BRAIN_API_KEY 等）
        overrideFromEnv(props);

        // ★ 记录环境变量覆盖的 key
        for (String key : props.stringPropertyNames()) {
            if (!CONFIG_SOURCES.containsKey(key)) {
                CONFIG_SOURCES.put(key, "环境");
            }
        }

        // 4. 赋值静态字段
        BRAIN_API_BASE = get(props, "llm.brain.apiBase", BRAIN_API_BASE);
        BRAIN_API_KEY = get(props, "llm.brain.apiKey", BRAIN_API_KEY);
        BRAIN_CHAT_MODEL = get(props, "llm.brain.chatModel", BRAIN_CHAT_MODEL);
        BRAIN_TEMPERATURE = getDouble(props, "llm.brain.temperature", BRAIN_TEMPERATURE);
        BRAIN_MAX_TOKENS = getInt(props, "llm.brain.maxTokens", BRAIN_MAX_TOKENS);
        BRAIN_STREAM = getBool(props, "llm.brain.stream", BRAIN_STREAM);
        BRAIN_THINKING_ENABLED = getBool(props, "llm.brain.thinkingEnabled", BRAIN_THINKING_ENABLED);
        BRAIN_THINKING_BUDGET_TOKENS = getInt(props, "llm.brain.thinkingBudgetTokens", BRAIN_THINKING_BUDGET_TOKENS);

        VISION_ENABLED = getBool(props, "vision.enabled", VISION_ENABLED);
        VISION_API_BASE = get(props, "vision.apiBase", "");
        if (VISION_API_BASE.isBlank()) VISION_API_BASE = BRAIN_API_BASE;
        VISION_API_KEY = get(props, "vision.apiKey", "");
        if (VISION_API_KEY.isBlank()) VISION_API_KEY = BRAIN_API_KEY;
        VISION_MODEL = get(props, "vision.model", "");
        if (VISION_MODEL.isBlank()) VISION_MODEL = BRAIN_CHAT_MODEL;
        VISION_MAX_TOKENS = getInt(props, "vision.maxTokens", VISION_MAX_TOKENS);
        VISION_TIMEOUT_SEC = getInt(props, "vision.timeoutSec", VISION_TIMEOUT_SEC);
        VISION_MAX_IMAGES = getInt(props, "vision.maxImages", VISION_MAX_IMAGES);

        NAPCAT_WS_URL = get(props, "napcat.wsUrl", NAPCAT_WS_URL);
        NAPCAT_WS_PORT = getInt(props, "napcat.wsPort", NAPCAT_WS_PORT);
        NAPCAT_HTTP_URL = get(props, "napcat.httpUrl", NAPCAT_HTTP_URL);
        NAPCAT_TOKEN = get(props, "napcat.token", NAPCAT_TOKEN);

        SEARCH_BRAVE_API_KEY = get(props, "search.braveApiKey", SEARCH_BRAVE_API_KEY);
        SEARCH_METASO_API_KEY = get(props, "search.metasoApiKey", SEARCH_METASO_API_KEY);

        WORKSPACE_DIR = get(props, "workspace.dir", WORKSPACE_DIR);

        MOTIVATION_CONFLICT_THRESHOLD = getDouble(props, "motivation.conflictThreshold", MOTIVATION_CONFLICT_THRESHOLD);
        MOTIVATION_NOVELTY_MIN_COUNT = getInt(props, "motivation.noveltyMinCount", MOTIVATION_NOVELTY_MIN_COUNT);
        MOTIVATION_DECAY_HALF_LIFE = getDouble(props, "motivation.decayHalfLife", MOTIVATION_DECAY_HALF_LIFE);
        MOTIVATION_VACUUM_THRESHOLD = getInt(props, "motivation.vacuumThreshold", MOTIVATION_VACUUM_THRESHOLD);
        MOTIVATION_REPORT_MAX_EXPERIENCES = getInt(props, "motivation.report.maxExperiences", MOTIVATION_REPORT_MAX_EXPERIENCES);
        MOTIVATION_CONFLICT_MAX_PAIRS = getInt(props, "motivation.conflict.maxPairs", MOTIVATION_CONFLICT_MAX_PAIRS);
        MOTIVATION_CONFLICT_MIN_TOKEN_COUNT = getInt(props, "motivation.conflict.minTokenCount", MOTIVATION_CONFLICT_MIN_TOKEN_COUNT);
        MOTIVATION_CONFLICT_COOCCUR_SCAN_LIMIT = getInt(props, "motivation.conflict.cooccurScanLimit", MOTIVATION_CONFLICT_COOCCUR_SCAN_LIMIT);
        MOTIVATION_CONFLICT_MAX_RESOLUTION_CHARS = getInt(props, "motivation.conflict.maxResolutionChars", MOTIVATION_CONFLICT_MAX_RESOLUTION_CHARS);

        CORE_TICK_MS = getLong(props, "core.tickMs", CORE_TICK_MS);
        CORE_ROUND_TIMEOUT_SEC = getInt(props, "core.roundTimeoutSec", CORE_ROUND_TIMEOUT_SEC);

        LLM_CONTEXT_MAX_MESSAGES = getInt(props, "llm.context.maxMessages", LLM_CONTEXT_MAX_MESSAGES);
        LLM_CONTEXT_KEEP_RATIO = getDouble(props, "llm.context.keepRatio", LLM_CONTEXT_KEEP_RATIO);
        LLM_CONTEXT_DIGEST_MAX_CHARS = getInt(props, "llm.context.digestMaxChars", LLM_CONTEXT_DIGEST_MAX_CHARS);

        LLM_CACHE_MAX_ENTRIES = getInt(props, "llm.cache.maxEntries", LLM_CACHE_MAX_ENTRIES);
        LLM_CACHE_MAX_SIZE_CHARS = getInt(props, "llm.cache.maxSizeChars", LLM_CACHE_MAX_SIZE_CHARS);

        MEMORY_AUTO_RECALL_TOPN = getInt(props, "memory.autoRecallTopN", MEMORY_AUTO_RECALL_TOPN);
        MEMORY_RECALL_MAX_RESULTS = getInt(props, "memory.recallMaxResults", MEMORY_RECALL_MAX_RESULTS);
        MEMORY_AUTO_RECALL_SCAN_LIMIT = getInt(props, "memory.autoRecallScanLimit", MEMORY_AUTO_RECALL_SCAN_LIMIT);
        MEMORY_HELPFUL_SCALE = getDouble(props, "memory.helpfulScaleFactor", MEMORY_HELPFUL_SCALE);
        OPPOSITION_DISPLAY_THRESHOLD = getDouble(props, "motivation.oppositionThreshold", OPPOSITION_DISPLAY_THRESHOLD);
        EQUIVALENT_TOKEN_THRESHOLD = getDouble(props, "motivation.equivalentTokenThreshold", EQUIVALENT_TOKEN_THRESHOLD);

        MSG_AGGREGATE_WAIT_MS = getLong(props, "msg.aggregateWaitMs", MSG_AGGREGATE_WAIT_MS);
        MSG_AGGREGATE_COOLDOWN_MS = getLong(props, "msg.aggregateCooldownMs", MSG_AGGREGATE_COOLDOWN_MS);
        MSG_AGGREGATE_MAX_MESSAGES = getInt(props, "msg.aggregateMaxMessages", MSG_AGGREGATE_MAX_MESSAGES);
        MSG_AGGREGATE_PRIVATE_MIN = getInt(props, "msg.aggregatePrivateMin", MSG_AGGREGATE_PRIVATE_MIN);
        MSG_AGGREGATE_GROUP_MIN = getInt(props, "msg.aggregateGroupMin", MSG_AGGREGATE_GROUP_MIN);
        MSG_AGGREGATE_CHAR_FLUSH = getInt(props, "msg.aggregateCharFlush", MSG_AGGREGATE_CHAR_FLUSH);
        MSG_AGGREGATE_MS_PER_CHAR = getDouble(props, "msg.aggregateMsPerChar", MSG_AGGREGATE_MS_PER_CHAR);
        MSG_AGGREGATE_MIN_WAIT_MS = getInt(props, "msg.aggregateMinWaitMs", MSG_AGGREGATE_MIN_WAIT_MS);

        STIMULUS_PRIVATE_WEIGHT = getDouble(props, "stimulus.privateWeight", STIMULUS_PRIVATE_WEIGHT);
        STIMULUS_GROUP_WEIGHT = getDouble(props, "stimulus.groupWeight", STIMULUS_GROUP_WEIGHT);
        STIMULUS_CONSOLE_WEIGHT = getDouble(props, "stimulus.consoleWeight", STIMULUS_CONSOLE_WEIGHT);
        STIMULUS_INTERNAL_WEIGHT = getDouble(props, "stimulus.internalWeight", STIMULUS_INTERNAL_WEIGHT);
        STIMULUS_LENGTH_DIVISOR = getInt(props, "stimulus.lengthDivisor", STIMULUS_LENGTH_DIVISOR);

        POOL_WAITING_WEIGHT = getDouble(props, "pool.waitingWeight", POOL_WAITING_WEIGHT);
        POOL_ENDOGENOUS_MARGIN = getDouble(props, "pool.endogenousMargin", POOL_ENDOGENOUS_MARGIN);
        POOL_PROCESSING_MAX_CHARS = getInt(props, "pool.processingMaxChars", POOL_PROCESSING_MAX_CHARS);

        CHAT_HISTORY_AUTO_COUNT = getInt(props, "chat.historyAutoCount", CHAT_HISTORY_AUTO_COUNT);
        CHAT_HISTORY_TOOL_COUNT = getInt(props, "chat.historyToolCount", CHAT_HISTORY_TOOL_COUNT);

        DB_URL = get(props, "db.url", DB_URL);
        DEBUG_AUTO_ENABLE = getBool(props, "debug.autoEnable", DEBUG_AUTO_ENABLE);
        CONFIG_AUTO_COMPLETE = getBool(props, "config.autoComplete", CONFIG_AUTO_COMPLETE);

        // ★ 自动写出完整配置文件
        if (CONFIG_AUTO_COMPLETE) {
            autoCompleteConfig();
        }
    }

    private static void overrideFromEnv(Properties props) {
        String brainKey = System.getenv("BRAIN_API_KEY");
        if (brainKey != null && !brainKey.isBlank()) props.setProperty("llm.brain.apiKey", brainKey);
        String sfKey = System.getenv("SILICONFLOW_API_KEY");
        if (sfKey != null && !sfKey.isBlank()) props.setProperty("llm.brain.apiKey", sfKey);

        String braveKey = System.getenv("BRAVE_API_KEY");
        if (braveKey != null && !braveKey.isBlank()) props.setProperty("search.braveApiKey", braveKey);
        String metasoKey = System.getenv("METASO_API_KEY");
        if (metasoKey != null && !metasoKey.isBlank()) props.setProperty("search.metasoApiKey", metasoKey);
    }

    private static String get(Properties p, String key, String def) {
        return p.getProperty(key, def);
    }
    private static int getInt(Properties p, String key, int def) {
        try { return Integer.parseInt(p.getProperty(key)); } catch (Exception e) { return def; }
    }
    private static long getLong(Properties p, String key, long def) {
        try { return Long.parseLong(p.getProperty(key)); } catch (Exception e) { return def; }
    }
    private static double getDouble(Properties p, String key, double def) {
        try { return Double.parseDouble(p.getProperty(key)); } catch (Exception e) { return def; }
    }
    private static boolean getBool(Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        return "true".equalsIgnoreCase(v.trim()) || "yes".equalsIgnoreCase(v.trim());
    }

    // ==========================================
    // 配置自动补全
    // ==========================================

    /** 单个配置项的元数据 */
    private record ConfigEntry(
            String key,
            String section,
            String description,
            String defaultValue,
            String currentValue,
            String source
    ) {}

    /**
     * 扫描全部配置项，写出完整的 application.properties.full。
     * 包含所有字段的注释、当前值、默认值和来源标记。
     * 方便调试、迁移和新项目搭建。
     */
    private static void autoCompleteConfig() {
        // ── 按分类构建配置注册表 ──
        java.util.List<ConfigEntry> registry = new java.util.ArrayList<>();

        // LLM 大脑模型
        registry.add(entry("llm.brain.apiBase",
                "LLM 大脑模型", "LLM API 地址 (OpenAI 兼容)",
                "http://127.0.0.1:3000/v1", BRAIN_API_BASE));
        registry.add(entry("llm.brain.apiKey",
                "LLM 大脑模型", "LLM API 密钥 (支持 SILICONFLOW_API_KEY 环境变量)",
                "", BRAIN_API_KEY));
        registry.add(entry("llm.brain.chatModel",
                "LLM 大脑模型", "对话模型名称",
                "deepseek-v4-flash-max", BRAIN_CHAT_MODEL));
        registry.add(entry("llm.brain.temperature",
                "LLM 大脑模型", "生成温度 (0~2, 越低越确定)",
                "0.6", String.valueOf(BRAIN_TEMPERATURE)));
        registry.add(entry("llm.brain.maxTokens",
                "LLM 大脑模型", "单次响应最大 token 数",
                "65535", String.valueOf(BRAIN_MAX_TOKENS)));
        registry.add(entry("llm.brain.stream",
                "LLM 大脑模型", "是否启用流式响应",
                "true", String.valueOf(BRAIN_STREAM)));
        registry.add(entry("llm.brain.thinkingEnabled",
                "LLM 大脑模型", "是否启用思维链 (DeepSeek thinking/reasoning, 开启后响应变慢但推理更深)",
                "false", String.valueOf(BRAIN_THINKING_ENABLED)));
        registry.add(entry("llm.brain.thinkingBudgetTokens",
                "LLM 大脑模型", "思维链预算 token 数 (仅在启用时生效, 0=不限制)",
                "2048", String.valueOf(BRAIN_THINKING_BUDGET_TOKENS)));

        // 视觉识别
        registry.add(entry("vision.enabled",
                "视觉识别 (识图LLM)", "是否启用图片识别 (关闭可避免VLM模型不兼容错误)",
                "true", String.valueOf(VISION_ENABLED)));
        registry.add(entry("vision.apiBase",
                "视觉识别 (识图LLM)", "视觉 API 地址 (为空则复用 llm.brain.apiBase)",
                "(复用brain)", nullableStr(VISION_API_BASE)));
        registry.add(entry("vision.apiKey",
                "视觉识别 (识图LLM)", "视觉 API 密钥 (为空则复用 llm.brain.apiKey)",
                "(复用brain)", nullableStr(VISION_API_KEY)));
        registry.add(entry("vision.model",
                "视觉识别 (识图LLM)", "视觉模型 (为空则复用 llm.brain.chatModel, 需支持多模态!)",
                "(复用brain)", nullableStr(VISION_MODEL)));
        registry.add(entry("vision.maxTokens",
                "视觉识别 (识图LLM)", "图片描述最大 token 数",
                "200", String.valueOf(VISION_MAX_TOKENS)));
        registry.add(entry("vision.timeoutSec",
                "视觉识别 (识图LLM)", "单张图片识别超时秒数",
                "15", String.valueOf(VISION_TIMEOUT_SEC)));
        registry.add(entry("vision.maxImages",
                "视觉识别 (识图LLM)", "单条消息最多识别图片数",
                "6", String.valueOf(VISION_MAX_IMAGES)));

        // NapcatQQ 适配器
        registry.add(entry("napcat.wsUrl",
                "NapcatQQ 适配器", "WebSocket 连接地址",
                "127.0.0.1", NAPCAT_WS_URL));
        registry.add(entry("napcat.wsPort",
                "NapcatQQ 适配器", "WebSocket 端口",
                "3001", String.valueOf(NAPCAT_WS_PORT)));
        registry.add(entry("napcat.httpUrl",
                "NapcatQQ 适配器", "HTTP API 地址",
                "http://127.0.0.1:3000", NAPCAT_HTTP_URL));
        registry.add(entry("napcat.token",
                "NapcatQQ 适配器", "Napcat 访问令牌",
                "", NAPCAT_TOKEN));

        // 搜索
        registry.add(entry("search.braveApiKey",
                "搜索服务", "Brave Search API 密钥",
                "", SEARCH_BRAVE_API_KEY));
        registry.add(entry("search.metasoApiKey",
                "搜索服务", "MetaSo 搜索 API 密钥",
                "", SEARCH_METASO_API_KEY));

        // 工作区
        registry.add(entry("workspace.dir",
                "工作区", "Bot 文件读写的工作目录",
                "workspace", WORKSPACE_DIR));

        // 认知循环
        registry.add(entry("core.tickMs",
                "认知循环", "主循环 tick 间隔 (毫秒)",
                "2000", String.valueOf(CORE_TICK_MS)));
        registry.add(entry("core.roundTimeoutSec",
                "认知循环", "单轮 LLM 调用超时 (秒)",
                "180", String.valueOf(CORE_ROUND_TIMEOUT_SEC)));

        // 全局上下文缓存
        registry.add(entry("llm.context.maxMessages",
                "全局上下文缓存", "消息列表最大条数 (超过触发压缩)",
                "42", String.valueOf(LLM_CONTEXT_MAX_MESSAGES)));
        registry.add(entry("llm.context.keepRatio",
                "全局上下文缓存", "压缩时保留后段比例 (0~1)",
                "0.30", String.valueOf(LLM_CONTEXT_KEEP_RATIO)));
        registry.add(entry("llm.context.digestMaxChars",
                "全局上下文缓存", "压缩摘要最大字符数",
                "3000", String.valueOf(LLM_CONTEXT_DIGEST_MAX_CHARS)));
        registry.add(entry("llm.cache.maxEntries",
                "全局上下文缓存", "触发缓存重置的消息条数阈值",
                "40", String.valueOf(LLM_CACHE_MAX_ENTRIES)));
        registry.add(entry("llm.cache.maxSizeChars",
                "全局上下文缓存", "触发缓存重置的总字符数阈值",
                "200000", String.valueOf(LLM_CACHE_MAX_SIZE_CHARS)));

        // 动机模型 — 核心
        registry.add(entry("motivation.conflictThreshold",
                "动机模型", "行动冲突检测阈值 (0~1, 越低越敏感)",
                "0.5", String.valueOf(MOTIVATION_CONFLICT_THRESHOLD)));
        registry.add(entry("motivation.noveltyMinCount",
                "动机模型", "新异 token 判定: 累计出现次数低于此值视为新异",
                "3", String.valueOf(MOTIVATION_NOVELTY_MIN_COUNT)));
        registry.add(entry("motivation.decayHalfLife",
                "动机模型", "共现计数半衰期 (轮次)",
                "500", String.valueOf(MOTIVATION_DECAY_HALF_LIFE)));
        registry.add(entry("motivation.vacuumThreshold",
                "动机模型", "真空清理: 计数低于此值的行可被删除",
                "1", String.valueOf(MOTIVATION_VACUUM_THRESHOLD)));

        // 动机模型 — 报告/冲突控制
        registry.add(entry("motivation.report.maxExperiences",
                "动机模型", "动机报告中最多展示几条关联历史经验",
                "5", String.valueOf(MOTIVATION_REPORT_MAX_EXPERIENCES)));
        registry.add(entry("motivation.conflict.maxPairs",
                "动机模型", "冲突对最大保留数 (写入DB和注入prompt)",
                "50", String.valueOf(MOTIVATION_CONFLICT_MAX_PAIRS)));
        registry.add(entry("motivation.conflict.minTokenCount",
                "动机模型", "冲突 token 最少累计出现次数 (碎片过滤)",
                "5", String.valueOf(MOTIVATION_CONFLICT_MIN_TOKEN_COUNT)));
        registry.add(entry("motivation.conflict.cooccurScanLimit",
                "动机模型", "共现统计扫描最近多少条经验",
                "300", String.valueOf(MOTIVATION_CONFLICT_COOCCUR_SCAN_LIMIT)));
        registry.add(entry("motivation.conflict.maxResolutionChars",
                "动机模型", "冲突方案文本在 prompt 中的最大总字符数",
                "8000", String.valueOf(MOTIVATION_CONFLICT_MAX_RESOLUTION_CHARS)));
        registry.add(entry("motivation.oppositionThreshold",
                "动机模型", "冲突度多高才在报告中显示 (旧阈值保留)",
                "0.5", String.valueOf(OPPOSITION_DISPLAY_THRESHOLD)));
        registry.add(entry("motivation.equivalentTokenThreshold",
                "动机模型", "coMatrix 行向量相似度多高视为等价 token",
                "0.6", String.valueOf(EQUIVALENT_TOKEN_THRESHOLD)));

        // 经验系统
        registry.add(entry("memory.autoRecallTopN",
                "经验系统", "自动检索匹配的经验条数 (Jaccard 推送给 LLM)",
                "3", String.valueOf(MEMORY_AUTO_RECALL_TOPN)));
        registry.add(entry("memory.recallMaxResults",
                "经验系统", "关键词搜索最大返回条数 (recall 工具)",
                "10", String.valueOf(MEMORY_RECALL_MAX_RESULTS)));
        registry.add(entry("memory.autoRecallScanLimit",
                "经验系统", "自动检索扫描的最近经验条数上限",
                "500", String.valueOf(MEMORY_AUTO_RECALL_SCAN_LIMIT)));
        registry.add(entry("memory.helpfulScaleFactor",
                "经验系统", "经验权重公式中 helpful_count 的缩放因子",
                "0.5", String.valueOf(MEMORY_HELPFUL_SCALE)));

        // 消息聚合
        registry.add(entry("msg.aggregateWaitMs",
                "消息聚合", "同源消息聚合等待时间 (毫秒)",
                "5000", String.valueOf(MSG_AGGREGATE_WAIT_MS)));
        registry.add(entry("msg.aggregateCooldownMs",
                "消息聚合", "聚合后同一来源冷却时间 (毫秒)",
                "5000", String.valueOf(MSG_AGGREGATE_COOLDOWN_MS)));
        registry.add(entry("msg.aggregateMaxMessages",
                "消息聚合", "聚合最大消息数 (攒够此数立即 flush)",
                "5", String.valueOf(MSG_AGGREGATE_MAX_MESSAGES)));
        registry.add(entry("msg.aggregatePrivateMin",
                "消息聚合", "私聊最低聚合消息数 (1=立即响应)",
                "1", String.valueOf(MSG_AGGREGATE_PRIVATE_MIN)));
        registry.add(entry("msg.aggregateGroupMin",
                "消息聚合", "群聊最低聚合消息数",
                "3", String.valueOf(MSG_AGGREGATE_GROUP_MIN)));
        registry.add(entry("msg.aggregateCharFlush",
                "消息聚合", "总字符数超过此值立即 flush",
                "200", String.valueOf(MSG_AGGREGATE_CHAR_FLUSH)));
        registry.add(entry("msg.aggregateMsPerChar",
                "消息聚合", "每个字符减少的等待毫秒数",
                "20", String.valueOf(MSG_AGGREGATE_MS_PER_CHAR)));
        registry.add(entry("msg.aggregateMinWaitMs",
                "消息聚合", "最低等待毫秒 (防止 0 等待)",
                "500", String.valueOf(MSG_AGGREGATE_MIN_WAIT_MS)));

        // 外源刺激权重
        registry.add(entry("stimulus.privateWeight",
                "外源刺激权重", "私聊消息的刺激权重",
                "0.7", String.valueOf(STIMULUS_PRIVATE_WEIGHT)));
        registry.add(entry("stimulus.groupWeight",
                "外源刺激权重", "群聊消息的刺激权重",
                "0.5", String.valueOf(STIMULUS_GROUP_WEIGHT)));
        registry.add(entry("stimulus.consoleWeight",
                "外源刺激权重", "控制台输入的刺激权重",
                "0.8", String.valueOf(STIMULUS_CONSOLE_WEIGHT)));
        registry.add(entry("stimulus.internalWeight",
                "外源刺激权重", "内部自动刺激的权重",
                "0.3", String.valueOf(STIMULUS_INTERNAL_WEIGHT)));
        registry.add(entry("stimulus.lengthDivisor",
                "外源刺激权重", "消息长度除数 (越长单位权重越低)",
                "20", String.valueOf(STIMULUS_LENGTH_DIVISOR)));

        // 行动池
        registry.add(entry("pool.waitingWeight",
                "行动池", "等待中 action 每轮的权重增量",
                "0.01", String.valueOf(POOL_WAITING_WEIGHT)));
        registry.add(entry("pool.endogenousMargin",
                "行动池", "内源 boost 的边际增量",
                "0.01", String.valueOf(POOL_ENDOGENOUS_MARGIN)));
        registry.add(entry("pool.processingMaxChars",
                "行动池", "LLM处理期间同源消息累加最大字符数 (超出从顶部裁剪旧内容)",
                "3000", String.valueOf(POOL_PROCESSING_MAX_CHARS)));

        // 聊天历史
        registry.add(entry("chat.historyAutoCount",
                "聊天历史", "自动拉取的聊天历史条数",
                "10", String.valueOf(CHAT_HISTORY_AUTO_COUNT)));
        registry.add(entry("chat.historyToolCount",
                "聊天历史", "get_chat_history 工具拉取的条数",
                "48", String.valueOf(CHAT_HISTORY_TOOL_COUNT)));

        // 数据库
        registry.add(entry("db.url",
                "数据库", "SQLite 数据库 JDBC URL",
                "jdbc:sqlite:mk65_motivation.db", DB_URL));

        // 调试
        registry.add(entry("debug.autoEnable",
                "调试", "启动时自动开启 DEBUG 日志",
                "false", String.valueOf(DEBUG_AUTO_ENABLE)));

        // 配置管理
        registry.add(entry("config.autoComplete",
                "配置管理", "启动时自动写出完整配置文件 application.properties.full",
                "true", String.valueOf(CONFIG_AUTO_COMPLETE)));

        // ── 写出文件 ──
        writeFullConfig(registry);
    }

    private static ConfigEntry entry(String key, String section, String desc,
                                      String defaultVal, String currentVal) {
        String source = CONFIG_SOURCES.getOrDefault(key, "默认");
        // 环境变量覆盖优先级高于文件
        if ("环境".equals(source)) {
            // 已经是环境，保持不变
        } else if (CONFIG_SOURCES.containsKey(key)) {
            source = "文件";
        } else {
            source = "默认";
        }
        return new ConfigEntry(key, section, desc, defaultVal, currentVal, source);
    }

    private static String nullableStr(String s) {
        return (s == null || s.isBlank()) ? "(空)" : s;
    }

    private static void writeFullConfig(java.util.List<ConfigEntry> registry) {
        Path target = Path.of("application.properties.full");
        StringBuilder sb = new StringBuilder();

        // 文件头
        sb.append("# ==========================================\n");
        sb.append("# MK65Mosire 完整配置文件 (自动生成)\n");
        sb.append("# 生成时间: ").append(java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
        sb.append("# \n");
        sb.append("# 标记说明:\n");
        sb.append("#   [文件] = 值来自 application.properties\n");
        sb.append("#   [环境] = 值来自环境变量\n");
        sb.append("#   [默认] = 使用内置默认值 (未在文件中显式配置)\n");
        sb.append("# \n");
        sb.append("# 用法:\n");
        sb.append("#   1. 复制此文件为 application.properties 即可获得完整配置模板\n");
        sb.append("#   2. 修改需要的字段后重启生效\n");
        sb.append("#   3. 空字符串表示未配置 (如 API key)\n");
        sb.append("# ==========================================\n\n");

        // 按分类分组输出
        java.util.Map<String, java.util.List<ConfigEntry>> grouped = new java.util.LinkedHashMap<>();
        for (ConfigEntry e : registry) {
            grouped.computeIfAbsent(e.section(), k -> new java.util.ArrayList<>()).add(e);
        }

        // 定义分类排序
        java.util.List<String> sectionOrder = java.util.List.of(
                "LLM 大脑模型",
                "视觉识别 (识图LLM)",
                "NapcatQQ 适配器",
                "搜索服务",
                "工作区",
                "认知循环",
                "全局上下文缓存",
                "动机模型",
                "经验系统",
                "消息聚合",
                "外源刺激权重",
                "行动池",
                "聊天历史",
                "数据库",
                "调试",
                "配置管理"
        );

        for (String section : sectionOrder) {
            java.util.List<ConfigEntry> entries = grouped.get(section);
            if (entries == null || entries.isEmpty()) continue;

            // 分类标题
            sb.append("# ──────────────────────────────────────────\n");
            sb.append("# ").append(section).append("\n");
            sb.append("# ──────────────────────────────────────────\n");

            int maxKeyLen = entries.stream().mapToInt(e -> e.key().length()).max().orElse(20);

            for (ConfigEntry e : entries) {
                // 注释行: 描述 + 默认值
                sb.append("# ").append(e.description()).append("\n");
                sb.append("# 默认: ").append(e.defaultValue())
                        .append("  |  来源: [").append(e.source()).append("]\n");

                // 配置行: key=value
                sb.append(e.key());
                // 对齐
                int pad = maxKeyLen - e.key().length() + 2;
                for (int i = 0; i < pad; i++) sb.append(' ');
                sb.append("= ").append(e.currentValue()).append("\n\n");
            }
        }

        // 写入文件
        try {
            Files.writeString(target, sb.toString());
            System.out.println("[MKConfig] ✅ 完整配置文件已写出: " + target.toAbsolutePath()
                    + " (" + registry.size() + " 个配置项)");
        } catch (Exception e) {
            System.err.println("[MKConfig] ⚠️ 写出完整配置文件失败: " + e.getMessage());
        }
    }
}
