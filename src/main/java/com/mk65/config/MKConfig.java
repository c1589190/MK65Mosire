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

    // ========== 认知循环 ==========
    public static long CORE_TICK_MS = 2000;
    public static int CORE_ROUND_TIMEOUT_SEC = 180;

    // ========== 全局上下文 ==========
    public static int LLM_CONTEXT_MAX_MESSAGES = 42;
    public static double LLM_CONTEXT_KEEP_RATIO = 0.30;
    public static int LLM_CONTEXT_DIGEST_MAX_CHARS = 3000;

    // ========== 经验系统 ==========
    public static int MEMORY_AUTO_RECALL_TOPN = 3;
    public static int MEMORY_RECALL_MAX_RESULTS = 10;
    public static int MEMORY_AUTO_RECALL_SCAN_LIMIT = 500;
    public static double MEMORY_HELPFUL_SCALE = 0.5;
    public static double OPPOSITION_DISPLAY_THRESHOLD = 0.5;   // 冲突度多高才在报告中显示
    public static double EQUIVALENT_TOKEN_THRESHOLD = 0.6;      // coMatrix行向量相似度多高视为等价token

    // ========== 消息聚合 ==========
    public static long MSG_AGGREGATE_WAIT_MS = 3000;
    public static long MSG_AGGREGATE_COOLDOWN_MS = 5000;
    public static int MSG_AGGREGATE_MAX_MESSAGES = 5;
    public static int MSG_AGGREGATE_PRIVATE_MIN = 1;
    public static int MSG_AGGREGATE_GROUP_MIN = 3;

    // ========== 外源刺激 ==========
    public static double STIMULUS_PRIVATE_WEIGHT = 0.7;
    public static double STIMULUS_GROUP_WEIGHT = 0.5;
    public static double STIMULUS_CONSOLE_WEIGHT = 0.8;
    public static double STIMULUS_INTERNAL_WEIGHT = 0.3;
    public static int STIMULUS_LENGTH_DIVISOR = 20;

    // ========== 行动池权重 ==========
    public static double POOL_WAITING_WEIGHT = 0.01;
    public static double POOL_ENDOGENOUS_MARGIN = 0.01;

    // ========== 聊天历史 ==========
    public static int CHAT_HISTORY_AUTO_COUNT = 10;
    public static int CHAT_HISTORY_TOOL_COUNT = 48;

    // ========== 数据库 ==========
    public static String DB_URL = "jdbc:sqlite:mk65_motivation.db";
    public static boolean DEBUG_AUTO_ENABLE = false;

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

        // 3. 环境变量覆盖（SILICONFLOW_API_KEY / BRAIN_API_KEY 等）
        overrideFromEnv(props);

        // 4. 赋值静态字段
        BRAIN_API_BASE = get(props, "llm.brain.apiBase", BRAIN_API_BASE);
        BRAIN_API_KEY = get(props, "llm.brain.apiKey", BRAIN_API_KEY);
        BRAIN_CHAT_MODEL = get(props, "llm.brain.chatModel", BRAIN_CHAT_MODEL);
        BRAIN_TEMPERATURE = getDouble(props, "llm.brain.temperature", BRAIN_TEMPERATURE);
        BRAIN_MAX_TOKENS = getInt(props, "llm.brain.maxTokens", BRAIN_MAX_TOKENS);
        BRAIN_STREAM = getBool(props, "llm.brain.stream", BRAIN_STREAM);

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

        CORE_TICK_MS = getLong(props, "core.tickMs", CORE_TICK_MS);
        CORE_ROUND_TIMEOUT_SEC = getInt(props, "core.roundTimeoutSec", CORE_ROUND_TIMEOUT_SEC);

        LLM_CONTEXT_MAX_MESSAGES = getInt(props, "llm.context.maxMessages", LLM_CONTEXT_MAX_MESSAGES);
        LLM_CONTEXT_KEEP_RATIO = getDouble(props, "llm.context.keepRatio", LLM_CONTEXT_KEEP_RATIO);
        LLM_CONTEXT_DIGEST_MAX_CHARS = getInt(props, "llm.context.digestMaxChars", LLM_CONTEXT_DIGEST_MAX_CHARS);

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

        STIMULUS_PRIVATE_WEIGHT = getDouble(props, "stimulus.privateWeight", STIMULUS_PRIVATE_WEIGHT);
        STIMULUS_GROUP_WEIGHT = getDouble(props, "stimulus.groupWeight", STIMULUS_GROUP_WEIGHT);
        STIMULUS_CONSOLE_WEIGHT = getDouble(props, "stimulus.consoleWeight", STIMULUS_CONSOLE_WEIGHT);
        STIMULUS_INTERNAL_WEIGHT = getDouble(props, "stimulus.internalWeight", STIMULUS_INTERNAL_WEIGHT);
        STIMULUS_LENGTH_DIVISOR = getInt(props, "stimulus.lengthDivisor", STIMULUS_LENGTH_DIVISOR);

        POOL_WAITING_WEIGHT = getDouble(props, "pool.waitingWeight", POOL_WAITING_WEIGHT);
        POOL_ENDOGENOUS_MARGIN = getDouble(props, "pool.endogenousMargin", POOL_ENDOGENOUS_MARGIN);

        CHAT_HISTORY_AUTO_COUNT = getInt(props, "chat.historyAutoCount", CHAT_HISTORY_AUTO_COUNT);
        CHAT_HISTORY_TOOL_COUNT = getInt(props, "chat.historyToolCount", CHAT_HISTORY_TOOL_COUNT);

        DB_URL = get(props, "db.url", DB_URL);
        DEBUG_AUTO_ENABLE = getBool(props, "debug.autoEnable", DEBUG_AUTO_ENABLE);
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
}
