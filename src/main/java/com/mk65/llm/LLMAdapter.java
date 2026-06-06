package com.mk65.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mk65.config.MKConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI 兼容 API 调用封装。
 * 维护全局消息列表，利用 API 层 prompt 前缀缓存提高命中率。
 *
 * 缓存策略：条目数或总大小超阈值时，清空全部缓存，
 * 将最后30%内容压缩为文本摘要，拼接到下一轮首个user消息前面。
 */
@Slf4j
public class LLMAdapter {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient client;

    private final String apiBase;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final int maxTokens;

    /** 全局消息列表 — system + 最近N轮对话 */
    private final List<JsonNode> globalMessages = new ArrayList<>();
    private String systemPromptText = "";

    private int maxEntries() { return MKConfig.LLM_CACHE_MAX_ENTRIES; }
    private int maxSizeChars() { return MKConfig.LLM_CACHE_MAX_SIZE_CHARS; }
    private int digestMaxChars() { return MKConfig.LLM_CONTEXT_DIGEST_MAX_CHARS; }
    /** 清空时保留的后段比例 */
    private static final double DIGEST_KEEP_RATIO = 0.30;
    /** 单条消息摘要截断长度 */
    private static final int SNIP_MAX_CHARS = 150;
    /** tool消息摘要截断长度 */
    private static final int TOOL_SNIP_MAX_CHARS = 80;
    /** body 大小安全线 (~1M token) */
    private static final int TOKEN_LIMIT_CHARS = 800_000;

    public LLMAdapter() {
        this.apiBase = MKConfig.BRAIN_API_BASE;
        this.apiKey = MKConfig.BRAIN_API_KEY;
        this.model = MKConfig.BRAIN_CHAT_MODEL;
        this.temperature = MKConfig.BRAIN_TEMPERATURE;
        this.maxTokens = MKConfig.BRAIN_MAX_TOKENS;

        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(MKConfig.CORE_ROUND_TIMEOUT_SEC, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 设置系统提示（仅首次调用，覆盖旧的）。
     * 系统提示始终在 messages[0]，后续轮次追加在后面。
     */
    public void setSystemPrompt(String prompt) {
        this.systemPromptText = prompt;
        globalMessages.clear();
        ObjectNode sys = mapper.createObjectNode();
        sys.put("role", "system");
        sys.put("content", prompt);
        globalMessages.add(sys);
        log.info("[LLM] 系统提示已设置 ({}chars)", prompt.length());
    }

    /**
     * 清空全局消息列表。保留system prompt。
     */
    public void clearContext() {
        globalMessages.clear();
        ObjectNode sys = mapper.createObjectNode();
        sys.put("role", "system");
        sys.put("content", systemPromptText);
        globalMessages.add(sys);
        log.info("[LLM] 全局上下文已清空 (system保留)");
    }

    /**
     * 调用 LLM。
     *
     * @param userMessage 用户消息（含动机报告和当前ActionText）
     * @param tools       工具定义列表
     * @return LLMResult
     */
    public LLMResult chat(String userMessage, ArrayNode tools) {
        // ★ 1. 检查缓存是否超阈值（超了先重置，拿到后30%摘要）
        String digest = maybeResetCache();

        // ★ 2. 如果有摘要（刚重置过），拼接到user消息前面，确保LLM有上下连续性
        if (digest != null) {
            userMessage = "[上下文摘要] 以下是之前对话的压缩记录：\n\n" + digest
                    + "\n\n---\n\n【当前输入】\n" + userMessage;
        }

        // 3. 创建user消息前，检查并修复：如果顶层已是user，删除旧的避免连续重复
        repairTopUserDuplicate();

        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        globalMessages.add(userMsg);

        // 4. 构建请求体
        ObjectNode body = buildRequestBody(tools);

        // ★ 5. 紧急保护：请求体过大时强制重置
        if (body.toString().length() > TOKEN_LIMIT_CHARS) {
            log.warn("[LLM] ⚠️ 请求体过大({}chars), 紧急重置", body.toString().length());
            // 先移除刚加的user消息
            if (!globalMessages.isEmpty()
                    && "user".equals(globalMessages.get(globalMessages.size() - 1).path("role").asText(""))) {
                globalMessages.remove(globalMessages.size() - 1);
            }
            digest = resetCacheAndDigest();
            if (digest != null) {
                userMessage = "[上下文摘要] 以下是之前对话的压缩记录：\n\n" + digest
                        + "\n\n---\n\n【当前输入】\n" + userMessage;
            }
            userMsg.put("content", userMessage);
            // 摘要重置后缓存只剩 system，再次确保无重复user
            repairTopUserDuplicate();
            globalMessages.add(userMsg);
            body = buildRequestBody(tools);
        }

        // 6. 验证消息序列合法性（兜底：非法时外科修复，不清空全缓存）
        String seqError = validateMessageSequence();
        if (seqError != null) {
            log.error("[LLM] ⚠️ 消息序列非法, 外科修复: {}", seqError);
            repairTopUserDuplicate();
            repairToolSequence();
            // 再次验证，仍非法才全清
            seqError = validateMessageSequence();
            if (seqError != null) {
                log.error("[LLM] ⚠️ 外科修复无效, 全清重建: {}", seqError);
                globalMessages.clear();
                ObjectNode sys = mapper.createObjectNode();
                sys.put("role", "system");
                sys.put("content", systemPromptText);
                globalMessages.add(sys);
                globalMessages.add(userMsg);
            }
            body = buildRequestBody(tools);
        }

        // 7. 计算请求体大小和消息概要
        String bodyStr = body.toString();
        int bodyBytes = bodyStr.getBytes(StandardCharsets.UTF_8).length;

        StringBuilder msgSizes = new StringBuilder();
        for (int i = 0; i < globalMessages.size(); i++) {
            JsonNode m = globalMessages.get(i);
            String role = m.path("role").asText("");
            int len = m.path("content").asText("").length();
            if (len > 0) {
                msgSizes.append(String.format("[%d]%s(%d) ", i, role, len));
            } else if (m.has("tool_calls")) {
                msgSizes.append(String.format("[%d]%s(tools×%d) ", i, role, m.path("tool_calls").size()));
            } else {
                msgSizes.append(String.format("[%d]%s(空) ", i, role));
            }
        }

        log.info("[LLM] 📤 请求 → {} | model={} | msgs={} | body={}KB | {}",
                apiBase, model, globalMessages.size(),
                String.format("%.1f", bodyBytes / 1024.0),
                msgSizes.toString().trim());

        long startMs = System.currentTimeMillis();

        try {
            String fullUrl = apiBase + (apiBase.endsWith("/") ? "" : "/") + "chat/completions";
            RequestBody reqBody = RequestBody.create(
                    MediaType.parse("application/json"), bodyStr);
            Request.Builder reqBuilder = new Request.Builder()
                    .url(fullUrl)
                    .post(reqBody)
                    .addHeader("Content-Type", "application/json");
            if (apiKey != null && !apiKey.isBlank()) {
                reqBuilder.addHeader("Authorization", "Bearer " + apiKey);
            }

            try (Response resp = client.newCall(reqBuilder.build()).execute()) {
                long elapsed = System.currentTimeMillis() - startMs;
                if (!resp.isSuccessful() || resp.body() == null) {
                    String errBody = "";
                    try { if (resp.body() != null) errBody = resp.body().string(); } catch (Exception ignored) {}
                    log.error("[LLM] ❌ HTTP {} ({}ms) | 响应: {}",
                            resp.code(), elapsed,
                            errBody.length() > 500 ? errBody.substring(0, 500) + "..." : errBody);

                    // 400类错误 → 上下文损坏 → 自动重置
                    if (resp.code() >= 400 && resp.code() < 500) {
                        log.warn("[LLM] 🔄 自动重置全局上下文 (HTTP {})", resp.code());
                        clearContext();
                    }
                    return LLMResult.error("HTTP " + resp.code(), elapsed);
                }
                String respStr = resp.body().string();
                log.info("[LLM] 📥 响应 {}ms | body={}KB | HTTP {}",
                        elapsed, String.format("%.1f", respStr.getBytes(StandardCharsets.UTF_8).length / 1024.0),
                        resp.code());
                JsonNode root = mapper.readTree(respStr);
                return parseAndCache(root, elapsed);
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startMs;
            if (!globalMessages.isEmpty()
                    && "user".equals(globalMessages.get(globalMessages.size() - 1).path("role").asText(""))) {
                globalMessages.remove(globalMessages.size() - 1);
            }
            log.error("[LLM] ❌ 调用失败 ({}ms): {}", elapsed, e.getMessage());
            return LLMResult.error("LLM调用失败: " + e.getMessage(), elapsed);
        }
    }

    /**
     * 将工具执行结果作为 tool role 消息追加到全局列表。
     * 调用时机：每执行完一个工具后立即调用。
     * 如果顶层已是 tool（上一条 assistant 已被清除），先移除旧 tool 避免连续重复。
     */
    public void appendToolResult(String toolCallId, String toolName, String result) {
        // 外科防护：顶层已是 tool → 删除旧的（可能来自损坏的缓存）
        if (!globalMessages.isEmpty()
                && "tool".equals(globalMessages.get(globalMessages.size() - 1).path("role").asText(""))) {
            globalMessages.remove(globalMessages.size() - 1);
            log.warn("[LLM] 🔧 移除连续tool重复, 再追加 tool={}", toolName);
        }
        ObjectNode toolMsg = mapper.createObjectNode();
        toolMsg.put("role", "tool");
        toolMsg.put("tool_call_id", toolCallId);
        toolMsg.put("name", toolName);
        toolMsg.put("content", result != null ? result : "");
        globalMessages.add(toolMsg);
    }

    /**
     * 直接追加 assistant 消息（用于没有 tool_calls 的纯文本响应，或其他需要外部控制的情况）。
     * parseAndCache 已经自动处理了有/无 tool_calls 的情况，这个方法暴露给外部用于特殊场景。
     */
    public void appendAssistantMessage(String content, List<ToolCall> toolCalls) {
        ObjectNode assistant = mapper.createObjectNode();
        assistant.put("role", "assistant");
        if (content != null && !content.isBlank()) {
            assistant.put("content", content);
        }
        if (toolCalls != null && !toolCalls.isEmpty()) {
            ArrayNode tcArray = assistant.putArray("tool_calls");
            for (ToolCall tc : toolCalls) {
                ObjectNode tcNode = tcArray.addObject();
                tcNode.put("id", tc.id());
                tcNode.put("type", "function");
                ObjectNode fn = tcNode.putObject("function");
                fn.put("name", tc.name());
                fn.put("arguments", tc.arguments());
            }
        }
        globalMessages.add(assistant);
    }

    public int getMessageCount() {
        return globalMessages.size();
    }

    /**
     * 验证消息序列合法性。返回null=合法，非null=错误描述。
     * 规则:
     * - 不能有两个连续的同role消息(user→user或assistant→assistant)
     * - tool消息前必须有assistant(含tool_calls)
     * - system只能出现在索引0
     */
    private String validateMessageSequence() {
        for (int i = 0; i < globalMessages.size(); i++) {
            String role = globalMessages.get(i).path("role").asText("");
            // system只能在索引0
            if ("system".equals(role) && i > 0) {
                return "system出现在索引" + i;
            }
            // 检查连续同role
            if (i > 0) {
                String prevRole = globalMessages.get(i - 1).path("role").asText("");
                if (role.equals(prevRole) && !"system".equals(role)) {
                    return String.format("连续两个%s (索引%d和%d)", role, i - 1, i);
                }
            }
            // tool前必须是assistant且含tool_calls
            if ("tool".equals(role) && i > 0) {
                String prevRole = globalMessages.get(i - 1).path("role").asText("");
                if (!"assistant".equals(prevRole)) {
                    return String.format("tool消息(索引%d)前不是assistant而是%s", i, prevRole);
                }
            }
        }
        return null;
    }

    /**
     * 外科修复：如果缓存顶层已是 user 消息，删除它。
     * 在添加新 user 消息前调用，防止 user→user 连续重复。
     */
    private void repairTopUserDuplicate() {
        if (!globalMessages.isEmpty()
                && "user".equals(globalMessages.get(globalMessages.size() - 1).path("role").asText(""))) {
            int removed = globalMessages.size() - 1;
            globalMessages.remove(removed);
            log.warn("[LLM] 🔧 移除顶层重复user (索引{}), 缓存剩余{}条", removed, globalMessages.size());
        }
    }

    /**
     * 外科修复：如果存在连续 tool 消息（孤儿tool），移除旧的。
     * tool 必须在 assistant(tool_calls) 之后，无 assistant 的 tool 是孤儿。
     */
    private void repairToolSequence() {
        for (int i = globalMessages.size() - 1; i >= 1; i--) {
            String role = globalMessages.get(i).path("role").asText("");
            if ("tool".equals(role)) {
                String prevRole = globalMessages.get(i - 1).path("role").asText("");
                if ("tool".equals(prevRole)) {
                    globalMessages.remove(i - 1);
                    log.warn("[LLM] 🔧 移除连续tool重复 (索引{}和{})", i - 1, i);
                }
            }
        }
    }

    // ==========================================
    // 内部
    // ==========================================

    private LLMResult parseAndCache(JsonNode root, long elapsedMs) {
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            // 失败时移除用户消息
            if (!globalMessages.isEmpty()
                    && "user".equals(globalMessages.get(globalMessages.size() - 1).path("role").asText(""))) {
                globalMessages.remove(globalMessages.size() - 1);
            }
            return LLMResult.error("LLM返回空choices", elapsedMs);
        }

        JsonNode first = choices.get(0);
        JsonNode message = first.path("message");
        String content = message.path("content").asText("");
        JsonNode toolCallsNode = message.path("tool_calls");

        List<ToolCall> tcList = new ArrayList<>();
        if (toolCallsNode.isArray()) {
            for (JsonNode tc : toolCallsNode) {
                String id = tc.path("id").asText("");
                JsonNode fn = tc.path("function");
                String name = fn.path("name").asText("");
                String args = fn.path("arguments").asText("");
                tcList.add(new ToolCall(id, name, args));
            }
            log.info("[LLM] 响应 ({}ms): {}个工具调用, content={}chars, messages={}",
                    elapsedMs, tcList.size(), content.length(), globalMessages.size());
        } else {
            log.info("[LLM] 响应 ({}ms): 纯文本, content={}chars, messages={}",
                    elapsedMs, content.length(), globalMessages.size());
        }

        // 缓存 assistant 消息
        appendAssistantMessage(content, tcList);

        return new LLMResult(content, tcList, elapsedMs, false);
    }

    // ==========================================
    // 缓存重置
    // ==========================================

    /**
     * 检查缓存是否超阈值（条目数 或 总大小）。
     * 如需要重置，清空缓存并返回后30%的文本摘要。
     *
     * @return 摘要文本，null表示不需要重置
     */
    private String maybeResetCache() {
        int entryCount = globalMessages.size();
        int totalSize = calculateTotalContentSize();

        if (entryCount <= maxEntries() && totalSize <= maxSizeChars()) {
            return null;
        }
        if (globalMessages.size() <= 1) {
            return null; // 只有system prompt，无需重置
        }

        return resetCacheAndDigest();
    }

    /**
     * 执行缓存重置：
     * 1. 取非system消息的最后30%转为文本摘要
     * 2. 清空全部缓存
     * 3. 重建system prompt（保持结构正常）
     *
     * @return 后30%的文本摘要
     */
    private String resetCacheAndDigest() {
        int nonSystemCount = globalMessages.size() - 1;
        int keepCount = Math.max(1, (int) (nonSystemCount * DIGEST_KEEP_RATIO));
        int summarizeFrom = globalMessages.size() - keepCount;
        int discardedCount = nonSystemCount - keepCount;

        StringBuilder sb = new StringBuilder();
        sb.append("[缓存重置 — 前").append(discardedCount).append("条已舍弃，保留后").append(keepCount).append("条摘要]\n");

        for (int i = summarizeFrom; i < globalMessages.size(); i++) {
            JsonNode msg = globalMessages.get(i);
            String role = msg.path("role").asText("");
            String content = msg.path("content").asText("");

            if (content.isBlank() && msg.has("tool_calls")) {
                JsonNode tc = msg.path("tool_calls");
                int tcCount = tc.isArray() ? tc.size() : 0;
                sb.append("  [assistant] 调用").append(tcCount).append("个工具\n");
            } else if ("tool".equals(role)) {
                String name = msg.path("name").asText("");
                String snip = content.length() > TOOL_SNIP_MAX_CHARS
                        ? content.substring(0, TOOL_SNIP_MAX_CHARS) + "..." : content;
                sb.append("  [tool:").append(name).append("] ").append(snip.replace("\n", " ")).append("\n");
            } else if (!content.isBlank()) {
                String snip = content.length() > SNIP_MAX_CHARS
                        ? content.substring(0, SNIP_MAX_CHARS) + "..." : content;
                sb.append("  [").append(role).append("] ").append(snip.replace("\n", " ")).append("\n");
            }
        }

        String digest = sb.toString().trim();
        if (digest.length() > digestMaxChars()) {
            digest = digest.substring(0, digestMaxChars()) + "...[截断]";
        }

        int oldCount = globalMessages.size();

        // 清空并重建
        globalMessages.clear();
        ObjectNode sys = mapper.createObjectNode();
        sys.put("role", "system");
        sys.put("content", systemPromptText);
        globalMessages.add(sys);

        log.info("[LLM] 🔄 缓存重置: {}条 → 清空, 后30%({}条)→摘要({}chars)",
                oldCount, oldCount - summarizeFrom, digest.length());

        return digest;
    }

    /**
     * 计算globalMessages中所有消息的内容总大小（字符数）。
     */
    private int calculateTotalContentSize() {
        int total = 0;
        for (JsonNode msg : globalMessages) {
            String content = msg.path("content").asText("");
            total += content.length();
            JsonNode tc = msg.path("tool_calls");
            if (tc.isArray()) {
                total += tc.toString().length();
            }
            String name = msg.path("name").asText("");
            if (!name.isBlank()) {
                total += name.length();
            }
        }
        return total;
    }

    /** 构建请求体（复用逻辑） */
    private ObjectNode buildRequestBody(ArrayNode tools) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.put("stream", false);
        ArrayNode messages = body.putArray("messages");
        for (JsonNode msg : globalMessages) messages.add(msg);
        if (tools != null && tools.size() > 0) {
            body.set("tools", tools);
            body.put("tool_choice", "auto");
        }
        return body;
    }

    // ==========================================
    // 数据类
    // ==========================================

    public record LLMResult(
            String content,
            List<ToolCall> toolCalls,
            long elapsedMs,
            boolean isError
    ) {
        public boolean hasToolCalls() { return toolCalls != null && !toolCalls.isEmpty(); }
        public static LLMResult error(String msg, long elapsedMs) {
            return new LLMResult(msg, List.of(), elapsedMs, true);
        }
    }

    public record ToolCall(String id, String name, String arguments) {}
}
