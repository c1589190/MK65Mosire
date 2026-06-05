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
    private static final int MAX_MESSAGES = 42;  // 超过此值触发压缩
    private static final double KEEP_RATIO = 0.30; // 压缩时保留后30%原样
    private String systemPromptText = "";

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
        // 追加用户消息
        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        globalMessages.add(userMsg);

        // 超限压缩：前70% → 摘要user消息，后30%原样保留
        compressContext();

        // 构建请求体
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.put("stream", false); // 强制非流式以保证上下文缓存命中

        ArrayNode messages = body.putArray("messages");
        for (JsonNode msg : globalMessages) {
            messages.add(msg);
        }

        if (tools != null && tools.size() > 0) {
            body.set("tools", tools);
            body.put("tool_choice", "auto");
        }

        long startMs = System.currentTimeMillis();

        try {
            String fullUrl = apiBase + (apiBase.endsWith("/") ? "" : "/") + "chat/completions";
            RequestBody reqBody = RequestBody.create(
                    MediaType.parse("application/json"), body.toString());
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
                    // 请求失败时移除刚才追回的用户消息，避免污染上下文
                    if (!globalMessages.isEmpty() && "user".equals(globalMessages.get(globalMessages.size() - 1).path("role").asText(""))) {
                        globalMessages.remove(globalMessages.size() - 1);
                    }
                    return LLMResult.error("HTTP " + resp.code(), elapsed);
                }
                String bodyStr = resp.body().string();
                JsonNode root = mapper.readTree(bodyStr);
                return parseAndCache(root, elapsed);
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startMs;
            // 同上，清理污染
            if (!globalMessages.isEmpty() && "user".equals(globalMessages.get(globalMessages.size() - 1).path("role").asText(""))) {
                globalMessages.remove(globalMessages.size() - 1);
            }
            log.error("[LLM] 调用失败 ({}ms): {}", elapsed, e.getMessage());
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
     * 压缩全局消息列表。
     *
     * 超限时：保留后 KEEP_RATIO(30%) 的消息原样不动，
     * 把前 70% 的消息压缩成一段摘要，和已有摘要合并，
     * 作为 user 消息塞在 system prompt 之后。
     */
    private void compressContext() {
        if (globalMessages.size() <= MAX_MESSAGES) return;

        // 1. 收集旧摘要（如果索引1是摘要消息）
        String oldDigest = "";
        int digestAt = -1;
        if (globalMessages.size() > 1
                && "user".equals(globalMessages.get(1).path("role").asText(""))
                && globalMessages.get(1).path("content").asText("").startsWith("[上下文摘要]")) {
            oldDigest = globalMessages.get(1).path("content").asText("");
            digestAt = 1;
        }

        // 2. 分割：后 30% 保留，前 70%（system+摘要之后的）压缩
        int keepCount = Math.max(1, (int) ((globalMessages.size() - 1) * KEEP_RATIO));
        int splitIndex = globalMessages.size() - keepCount;
        if (splitIndex <= 1) splitIndex = 2; // 至少保留 system + 一轮

        // 3. 扫描要压缩的消息，生成摘要
        StringBuilder newDigest = new StringBuilder(oldDigest);
        if (!oldDigest.isBlank()) newDigest.append("\n---\n");
        newDigest.append("[最近活动 ").append(globalMessages.size() - splitIndex - keepCount + 1)
                .append("条消息的压缩]\n");

        int start = digestAt > 0 ? digestAt + 1 : 1; // 跳过system和已有摘要
        int end = splitIndex;
        for (int i = start; i < end; i++) {
            JsonNode msg = globalMessages.get(i);
            String role = msg.path("role").asText("");
            String content = msg.path("content").asText("");
            if (content.isBlank()) continue;

            if ("tool".equals(role)) {
                String name = msg.path("name").asText("");
                String snip = content.length() > 80 ? content.substring(0, 80) + "..." : content;
                newDigest.append("  [").append(name).append("] ").append(snip.replace("\n", " ")).append("\n");
            } else {
                String snip = content.length() > 150 ? content.substring(0, 150) + "..." : content;
                newDigest.append("  [").append(role).append("] ").append(snip.replace("\n", " ")).append("\n");
            }
        }

        // 4. 截断摘要
        String digest = newDigest.toString().trim();
        if (digest.length() > 3000) {
            digest = digest.substring(0, 3000) + "...[截断]";
        }

        // 5. 提取保留的消息（后30%）
        List<JsonNode> keep = new ArrayList<>();
        for (int i = splitIndex; i < globalMessages.size(); i++) {
            keep.add(globalMessages.get(i));
        }

        // 6. 重建列表
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

        log.info("[LLM] 🔄 上下文压缩: {}→{}条 (摘要{}chars, 保留{}条)",
                end - start + keep.size() + 2, globalMessages.size(), digest.length(), keep.size());
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
