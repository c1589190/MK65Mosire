package com.mk65.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mk65.config.MKConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.BufferedSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI 兼容 API 调用封装。
 * 支持流式和非流式。返回结构化结果。
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
    private final boolean stream;

    public LLMAdapter() {
        this.apiBase = MKConfig.BRAIN_API_BASE;
        this.apiKey = MKConfig.BRAIN_API_KEY;
        this.model = MKConfig.BRAIN_CHAT_MODEL;
        this.temperature = MKConfig.BRAIN_TEMPERATURE;
        this.maxTokens = MKConfig.BRAIN_MAX_TOKENS;
        this.stream = MKConfig.BRAIN_STREAM;

        this.client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(MKConfig.CORE_ROUND_TIMEOUT_SEC, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 调用 LLM，返回结构化结果。
     *
     * @param systemPrompt 系统指令
     * @param userMessage  用户消息（含动机报告）
     * @param tools        工具定义列表
     * @return LLMResult 包含 content, toolCalls, 耗时等
     */
    public LLMResult chat(String systemPrompt, String userMessage, ArrayNode tools) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", temperature);
        body.put("max_tokens", maxTokens);
        body.put("stream", stream);

        ArrayNode messages = body.putArray("messages");

        ObjectNode sysMsg = messages.addObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);

        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);

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

            if (stream) {
                return executeStream(reqBuilder.build(), startMs);
            } else {
                return executeNonStream(reqBuilder.build(), startMs);
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - startMs;
            log.error("[LLM] 调用失败 ({}ms): {}", elapsed, e.getMessage());
            return LLMResult.error("LLM调用失败: " + e.getMessage(), elapsed);
        }
    }

    private LLMResult executeNonStream(Request request, long startMs) throws IOException {
        try (Response resp = client.newCall(request).execute()) {
            long elapsed = System.currentTimeMillis() - startMs;
            if (!resp.isSuccessful() || resp.body() == null) {
                return LLMResult.error("HTTP " + resp.code(), elapsed);
            }
            String bodyStr = resp.body().string();
            JsonNode root = mapper.readTree(bodyStr);
            return parseResponse(root, elapsed);
        }
    }

    private LLMResult executeStream(Request request, long startMs) throws IOException {
        // 强制关流用非流式请求
        Request nonStreamReq = request.newBuilder()
                .post(modifyBodyForNonStream(request))
                .build();
        return executeNonStream(nonStreamReq, startMs);
    }

    private RequestBody modifyBodyForNonStream(Request originalReq) throws IOException {
        // 读取原请求体，修改 stream=false
        okio.Buffer buffer = new okio.Buffer();
        RequestBody body = originalReq.body();
        if (body == null) return null;
        body.writeTo(buffer);
        String bodyStr = buffer.readUtf8();
        JsonNode root = mapper.readTree(bodyStr);
        ((ObjectNode) root).put("stream", false);
        return RequestBody.create(MediaType.parse("application/json"), root.toString());
    }

    private LLMResult parseResponse(JsonNode root, long elapsedMs) {
        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return LLMResult.error("LLM返回空choices", elapsedMs);
        }

        JsonNode first = choices.get(0);
        JsonNode message = first.path("message");
        String content = message.path("content").asText("");
        JsonNode toolCalls = message.path("tool_calls");

        List<ToolCall> tcList = new ArrayList<>();
        if (toolCalls.isArray()) {
            for (JsonNode tc : toolCalls) {
                String id = tc.path("id").asText("");
                JsonNode fn = tc.path("function");
                String name = fn.path("name").asText("");
                String args = fn.path("arguments").asText("");
                tcList.add(new ToolCall(id, name, args));
            }
            log.info("[LLM] 响应 ({}ms): {}个工具调用, content={}chars",
                    elapsedMs, tcList.size(), content.length());
        } else {
            log.info("[LLM] 响应 ({}ms): 纯文本, content={}chars", elapsedMs, content.length());
        }

        return new LLMResult(content, tcList, elapsedMs, false);
    }

    /**
     * LLM 调用结果。
     */
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

    /**
     * 单次工具调用。
     */
    public record ToolCall(String id, String name, String arguments) {}
}
