package com.mk65.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mk65.config.MKConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * NapcatQQ WebSocket 适配器。
 * 适配正向WS + HTTP API。
 */
@Slf4j
public class NapcatAdapter extends WebSocketClient implements Adapter {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient httpClient;
    private final String httpBase;
    private final String token;

    private BiConsumer<String, String> messageCallback;
    private volatile boolean connected = false;
    private String selfId = "";

    public NapcatAdapter() throws URISyntaxException {
        super(buildWsUri(), buildHeaders(MKConfig.NAPCAT_TOKEN));
        this.httpBase = MKConfig.NAPCAT_HTTP_URL;
        this.token = MKConfig.NAPCAT_TOKEN;

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();

        this.setConnectionLostTimeout(60);
    }

    @Override
    public void start() {
        try {
            connectBlocking();
            fetchSelfId();
            log.info("[Napcat] ✅ 已连接: ws={} http={}",
                    MKConfig.NAPCAT_WS_URL + ":" + MKConfig.NAPCAT_WS_PORT, httpBase);
        } catch (InterruptedException e) {
            log.error("[Napcat] 连接被中断", e);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("[Napcat] 连接失败", e);
        }
    }

    @Override
    public void stop() {
        try { closeBlocking(); } catch (Exception ignored) {}
    }

    @Override
    public boolean isConnected() { return connected; }

    @Override
    public void sendGroupMsg(long groupId, String message) {
        log.info("[Napcat] 📤 发送群聊 → 群:{} | {}chars", groupId, message.length());
        httpPost("/send_group_msg", Map.of("group_id", groupId, "message", message));
    }

    @Override
    public void sendPrivateMsg(long userId, String message) {
        log.info("[Napcat] 📤 发送私聊 → 用户:{} | {}chars", userId, message.length());
        httpPost("/send_private_msg", Map.of("user_id", userId, "message", message));
    }

    @Override
    public void setMessageCallback(BiConsumer<String, String> callback) {
        this.messageCallback = callback;
    }

    // ── WebSocket 事件 ──

    @Override
    public void onOpen(ServerHandshake handshake) {
        connected = true;
        log.info("[Napcat] WebSocket 链路已建立");
    }

    @Override
    public void onMessage(String raw) {
        try {
            JsonNode msg = mapper.readTree(raw);
            String postType = msg.path("post_type").asText("");
            String messageType = msg.path("message_type").asText("");

            if (!"message".equals(postType)) return;
            String text = msg.path("raw_message").asText("");
            if (text.isBlank()) return;

            String source;
            String senderName = msg.path("sender").path("nickname").asText("");
            if (senderName.isBlank()) senderName = String.valueOf(msg.path("sender").path("user_id").asLong());

            if ("group".equals(messageType)) {
                long groupId = msg.path("group_id").asLong();
                source = "qq_group:" + groupId;
                log.info("[Napcat] 📨 群聊消息 | 群:{} | 发送者:{} | {}chars",
                        groupId, senderName, text.length());
            } else if ("private".equals(messageType)) {
                long userId = msg.path("user_id").asLong();
                source = "qqid:" + userId;
                log.info("[Napcat] 📨 私聊消息 | 发送者:{}({}) | {}chars",
                        senderName, userId, text.length());
            } else {
                return;
            }

            if (messageCallback != null) {
                messageCallback.accept(source, text);
            }
        } catch (Exception e) {
            log.warn("[Napcat] 消息解析失败: {}", raw.length() > 100 ? raw.substring(0, 100) : raw);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        connected = false;
        log.warn("[Napcat] WS连接关闭: code={}, reason={}, remote={}", code, reason, remote);
    }

    @Override
    public void onError(Exception ex) {
        log.error("[Napcat] WS错误", ex);
    }

    // ── HTTP 辅助 ──

    private void httpPost(String endpoint, Map<String, Object> params) {
        try {
            String url = httpBase + endpoint;
            ObjectNode body = mapper.createObjectNode();
            for (Map.Entry<String, Object> e : params.entrySet()) {
                Object v = e.getValue();
                if (v instanceof String) body.put(e.getKey(), (String) v);
                else if (v instanceof Long) body.put(e.getKey(), (Long) v);
                else if (v instanceof Integer) body.put(e.getKey(), (Integer) v);
                else if (v instanceof ArrayNode) body.set(e.getKey(), (ArrayNode) v);
            }

            RequestBody reqBody = RequestBody.create(
                    MediaType.parse("application/json"), body.toString());
            Request.Builder reqBuilder = new Request.Builder().url(url).post(reqBody);
            if (token != null && !token.isBlank()) {
                reqBuilder.addHeader("Authorization", "Bearer " + token);
            }

            try (Response resp = httpClient.newCall(reqBuilder.build()).execute()) {
                if (!resp.isSuccessful()) {
                    log.warn("[Napcat] HTTP {} → {}", endpoint, resp.code());
                }
            }
        } catch (Exception e) {
            log.error("[Napcat] HTTP POST 异常: {}", endpoint, e);
        }
    }

    private void fetchSelfId() {
        try {
            String url = httpBase + "/get_login_info";
            Request.Builder reqBuilder = new Request.Builder().url(url).get();
            if (token != null && !token.isBlank()) reqBuilder.addHeader("Authorization", "Bearer " + token);
            try (Response resp = httpClient.newCall(reqBuilder.build()).execute()) {
                if (resp.isSuccessful() && resp.body() != null) {
                    JsonNode data = mapper.readTree(resp.body().string()).path("data");
                    selfId = data.path("user_id").asText("");
                    log.info("[Napcat] 获取到自身QQ: {}", selfId);
                }
            }
        } catch (Exception e) {
            log.warn("[Napcat] 获取自身QQ失败", e);
        }
    }

    public String getSelfId() { return selfId; }

    private static URI buildWsUri() throws URISyntaxException {
        String url = MKConfig.NAPCAT_WS_URL;
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            url = "ws://" + url + ":" + MKConfig.NAPCAT_WS_PORT;
        }
        return new URI(url);
    }

    private static Map<String, String> buildHeaders(String token) {
        Map<String, String> headers = new HashMap<>();
        if (token != null && !token.isBlank()) {
            headers.put("Authorization", "Bearer " + token);
        }
        return headers;
    }
}
