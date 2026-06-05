package com.mk65.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mk65.config.MKConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI 兼容 API 调用封装。
 * 维护全局消息列表，利用 API 层 prompt 前缀缓存提高命中率。
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

    /** 全局消息列表 — system [+ 压缩摘要] + 最近N轮对话 */
    private final List<JsonNode> globalMessages = new ArrayList<>();
    private String systemPromptText = "";

    private int maxMessages() { return MKConfig.LLM_CONTEXT_MAX_MESSAGES; }
    private double keepRatio() { return MKConfig.LLM_CONTEXT_KEEP_RATIO; }
    private int digestMaxChars() { return MKConfig.LLM_CONTEXT_DIGEST_MAX_CHARS; }
    private static final int TOKEN_LIMIT_CHARS = 800_000; // ~1M token 的安全线

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
        // 先创建user消息（暂时不入globalMessages）
        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);

        // ★ 合并：如果已有摘要user消息（索引1），新消息追到摘要后面
        if (globalMessages.size() > 1
                && "user".equals(globalMessages.get(1).path("role").asText(""))
                && globalMessages.get(1).path("content").asText("").startsWith("[上下文摘要]")) {
            String merged = globalMessages.get(1).path("content").asText("")
                    + "\n\n---\n\n【当前输入】\n" + userMessage;
            ((com.fasterxml.jackson.databind.node.ObjectNode) globalMessages.get(1)).put("content", merged);
            // 不单独添加userMsg，不产生连续user
        } else {
            globalMessages.add(userMsg);
        }

        // 超限压缩
        compressContext();

        // ★ token超限保护：body过大时强制压缩再试
        ObjectNode body = buildRequestBody(tools);
        if (body.toString().length() > TOKEN_LIMIT_CHARS) {
            log.warn("[LLM] ⚠️ 请求体过大({}chars), 强制压缩", body.toString().length());
            forceCompress();
            // 合并新消息到压缩后的摘要
            if (globalMessages.size() > 1
                    && "user".equals(globalMessages.get(1).path("role").asText(""))
                    && globalMessages.get(1).path("content").asText("").startsWith("[上下文摘要]")) {
                String merged = globalMessages.get(1).path("content").asText("")
                        + "\n\n---\n\n【当前输入】\n" + userMessage;
                ((com.fasterxml.jackson.databind.node.ObjectNode) globalMessages.get(1)).put("content", merged);
            } else {
                globalMessages.add(userMsg);
            }
            body = buildRequestBody(tools);
        }

        // 验证序列
        String seqError = validateMessageSequence();
        if (seqError != null) {
            log.error("[LLM] ⚠️ 消息序列非法, 重置: {}", seqError);
            globalMessages.clear();
            ObjectNode sys = mapper.createObjectNode();
            sys.put("role", "system");
            sys.put("content", systemPromptText);
            globalMessages.add(sys);
            globalMessages.add(userMsg);
            body = buildRequestBody(tools);
        }

        // 计算请求体大小和消息概要
        String bodyStr = body.toString();
        int bodyBytes = bodyStr.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;

        // 每条消息的角色顺序
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
                        elapsed, String.format("%.1f", respStr.getBytes(java.nio.charset.StandardCharsets.UTF_8).length / 1024.0),
                        resp.code());
                JsonNode root = mapper.readTree(respStr);
                return parseAndCache(root, elapsed);
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startMs;
            if (!globalMessages.isEmpty() && "user".equals(globalMessages.get(globalMessages.size() - 1).path("role").asText(""))) {
                globalMessages.remove(globalMessages.size() - 1);
            }
            log.error("[LLM] ❌ 调用失败 ({}ms): {}", elapsed, e.getMessage());
            return LLMResult.error("LLM调用失败: " + e.getMessage(), elapsed);
        }
    }

    /**
     * 将工具执行结果作为 tool role 消息追加到全局列表。
     * 调用时机：每执行完一个工具后立即调用。
     */
    public void appendToolResult(String toolCallId, String toolName, String result) {
        ObjectNode toolMsg = mapper.createObjectNode();
        toolMsg.put("role", "tool");
        toolMsg.put("tool_call_id", toolCallId);
        toolMsg.put("name", toolName);
        toolMsg.put("content", result != null ? result : "");
        globalMessages.add(toolMsg);
        compressContext();
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
        compressContext();
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

    // ==========================================
    // 内部
    // ==========================================

    private LLMResult parseAndCache(JsonNode root, long elapsedMs) {
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            // 失败时移除用户消息
            if (!globalMessages.isEmpty() && "user".equals(globalMessages.get(globalMessages.size() - 1).path("role").asText(""))) {
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

    /**
     * 强制压缩（不管消息条数是否超限，token爆炸保护）。
     */
    private void forceCompress() {
        if (globalMessages.size() <= 3) return; // system + 摘要 + user 已经压不了

        // 1. 收集已有摘要
        String oldDigest = "";
        if (globalMessages.size() > 1
                && "user".equals(globalMessages.get(1).path("role").asText(""))
                && globalMessages.get(1).path("content").asText("").startsWith("[上下文摘要]")) {
            oldDigest = globalMessages.get(1).path("content").asText("");
        }

        // 2. 扫描最近N条压缩
        int keepCount = Math.max(2, (int) ((globalMessages.size() - 1) * 0.15)); // 只保留15%
        int splitIndex = globalMessages.size() - keepCount;

        // 后30%中只保留从第一个user开始的消息（跳过残余tool/assistant）
        while (splitIndex < globalMessages.size()
                && !"user".equals(globalMessages.get(splitIndex).path("role").asText(""))) {
            splitIndex++;
        }

        StringBuilder sb = new StringBuilder(oldDigest);
        if (!oldDigest.isBlank()) sb.append("\n---\n");
        sb.append("[最近压缩]\n");

        for (int i = Math.max(1, splitIndex - 20); i < splitIndex; i++) {
            JsonNode msg = globalMessages.get(i);
            String role = msg.path("role").asText("");
            String content = msg.path("content").asText("");
            if (content.isBlank()) continue;
            String snip = content.length() > 120 ? content.substring(0, 120) + "..." : content;
            sb.append("  [").append(role).append("] ").append(snip.replace("\n", " ")).append("\n");
        }

        String digest = sb.toString().trim();
        if (digest.length() > digestMaxChars()) digest = digest.substring(0, digestMaxChars()) + "...[截断]";

        // 保留从splitIndex开始的有效消息
        List<JsonNode> keep = new ArrayList<>();
        for (int i = splitIndex; i < globalMessages.size(); i++) keep.add(globalMessages.get(i));

        globalMessages.clear();
        ObjectNode sys = mapper.createObjectNode();
        sys.put("role", "system");
        sys.put("content", systemPromptText);
        globalMessages.add(sys);
        ObjectNode dm = mapper.createObjectNode();
        dm.put("role", "user");
        dm.put("content", "[上下文摘要] 以下是之前对话的压缩记录：\n\n" + digest);
        globalMessages.add(dm);
        globalMessages.addAll(keep);

        log.info("[LLM] 🔄 强制压缩: →{}条 (摘要{}chars)", globalMessages.size(), digest.length());
    }

    /**
     * 普通压缩：超限时前70%丢弃，后30%原样保留并压缩摘要。
     */
    private void compressContext() {
        if (globalMessages.size() <= maxMessages()) return;

        String oldDigest = "";
        if (globalMessages.size() > 1
                && "user".equals(globalMessages.get(1).path("role").asText(""))
                && globalMessages.get(1).path("content").asText("").startsWith("[上下文摘要]")) {
            oldDigest = globalMessages.get(1).path("content").asText("");
        }

        int nonSystemCount = globalMessages.size() - 1 - (oldDigest.isEmpty() ? 0 : 1);
        int keepCount = Math.max(2, (int) (nonSystemCount * keepRatio()));
        int splitIndex = globalMessages.size() - keepCount;

        // ★ 后30%从第一个user开始（跳过残留的tool/assistant）
        while (splitIndex < globalMessages.size()
                && !"user".equals(globalMessages.get(splitIndex).path("role").asText(""))) {
            splitIndex++;
        }

        StringBuilder sb = new StringBuilder();
        if (!oldDigest.isBlank()) sb.append(oldDigest).append("\n---\n");
        sb.append("[最近 ").append(keepCount).append(" 条消息的压缩]\n");

        for (int i = Math.max(1, splitIndex - 30); i < splitIndex; i++) {
            JsonNode msg = globalMessages.get(i);
            String role = msg.path("role").asText("");
            String content = msg.path("content").asText("");
            if (content.isBlank()) continue;
            if ("tool".equals(role)) {
                String name = msg.path("name").asText("");
                String snip = content.length() > 80 ? content.substring(0, 80) + "..." : content;
                sb.append("  [").append(name).append("] ").append(snip.replace("\n", " ")).append("\n");
            } else {
                String snip = content.length() > 150 ? content.substring(0, 150) + "..." : content;
                sb.append("  [").append(role).append("] ").append(snip.replace("\n", " ")).append("\n");
            }
        }

        String digest = sb.toString().trim();
        if (digest.length() > digestMaxChars()) digest = digest.substring(0, digestMaxChars()) + "...[截断]";

        List<JsonNode> keep = new ArrayList<>();
        for (int i = splitIndex; i < globalMessages.size(); i++) keep.add(globalMessages.get(i));

        globalMessages.clear();
        ObjectNode sys = mapper.createObjectNode();
        sys.put("role", "system");
        sys.put("content", systemPromptText);
        globalMessages.add(sys);
        ObjectNode dm = mapper.createObjectNode();
        dm.put("role", "user");
        dm.put("content", "[上下文摘要] 以下是之前对话的压缩记录：\n\n" + digest);
        globalMessages.add(dm);
        globalMessages.addAll(keep);

        log.info("[LLM] 🔄 压缩: {}条→{}条 (摘要{}chars, 保留{}条)",
                keep.size() + (splitIndex - 1) + 2, globalMessages.size(), digest.length(), keep.size());
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
