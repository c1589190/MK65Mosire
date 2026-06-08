package com.mk65.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mk65.config.MKConfig;
import com.mk65.vision.VisionService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
    private volatile boolean shouldReconnect = true;
    private volatile boolean reconnecting = false;
    private volatile boolean reconnectScheduled = false;
    private int reconnectAttempts = 0;
    private long connectedSince = 0; // epoch millis, 用于判断稳定连接
    private String selfId = "";

    private static final int HEARTBEAT_INTERVAL_SEC = 30;
    private static final int INITIAL_RECONNECT_DELAY_SEC = 5;
    private static final int MAX_RECONNECT_BACKOFF_SEC = 300;
    /** 连接需要稳定保持多少秒才重置重连计数 (防止 Napcat 秒关导致退避永不增长) */
    private static final int MIN_STABLE_CONNECT_SEC = 10;

    /** 图片消息异步处理器：单线程保证消息顺序，不阻塞 WebSocket */
    private final ExecutorService imageProcessor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "napcat-image");
        t.setDaemon(true);
        return t;
    });

    /** 心跳 + 重连调度器 */
    private final ScheduledExecutorService keepAliveScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "napcat-keepalive");
        t.setDaemon(true);
        return t;
    });

    public NapcatAdapter() throws URISyntaxException {
        super(buildWsUri(), buildHeaders(MKConfig.NAPCAT_WS_TOKEN));
        this.httpBase = MKConfig.NAPCAT_HTTP_URL;
        this.token = MKConfig.NAPCAT_HTTP_TOKEN;  // HTTP 专用 token

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();

        // ★ 禁用库内置 connectionLostChecker，改用自主心跳 ping 维持连接
        this.setConnectionLostTimeout(0);
    }

    @Override
    public void start() {
        // ★ 启动心跳 ping：每 30s 发送一次，防止 Napcat 因空闲断开连接
        keepAliveScheduler.scheduleAtFixedRate(
                this::sendHeartbeat,
                HEARTBEAT_INTERVAL_SEC, HEARTBEAT_INTERVAL_SEC, TimeUnit.SECONDS);

        try {
            connectBlocking();
            connected = true;
            connectedSince = System.currentTimeMillis();
            reconnectScheduled = false;
            fetchSelfId();
            log.info("[Napcat] ✅ 已连接: ws={}:{} http={}",
                    MKConfig.NAPCAT_WS_URL, MKConfig.NAPCAT_WS_PORT, httpBase);
        } catch (InterruptedException e) {
            connected = false;
            log.warn("[Napcat] 连接被中断, 将自动重连...");
            Thread.currentThread().interrupt();
            scheduleReconnect();
        } catch (Exception e) {
            connected = false;
            log.warn("[Napcat] 连接失败 ({}), 将自动重连...", e.getMessage());
            scheduleReconnect();
        }
    }

    @Override
    public void stop() {
        shouldReconnect = false;
        keepAliveScheduler.shutdown();
        try { closeBlocking(); } catch (Exception ignored) {}
        log.info("[Napcat] ⏹️ 适配器已停止");
    }

    @Override
    public boolean isConnected() { return connected; }

    @Override
    public String sendGroupMsg(long groupId, String message) {
        log.info("[Napcat] 📤 发送群聊 → 群:{} | {}chars", groupId, message.length());
        return httpPost("/send_group_msg", Map.of("group_id", groupId, "message", message));
    }

    @Override
    public String sendPrivateMsg(long userId, String message) {
        log.info("[Napcat] 📤 发送私聊 → 用户:{} | {}chars", userId, message.length());
        return httpPost("/send_private_msg", Map.of("user_id", userId, "message", message));
    }

    @Override
    public void setMessageCallback(BiConsumer<String, String> callback) {
        this.messageCallback = callback;
    }

    // ── WebSocket 事件 ──

    @Override
    public void onOpen(ServerHandshake handshake) {
        connected = true;
        connectedSince = System.currentTimeMillis();
        reconnecting = false;
        log.info("[Napcat] ✅ WebSocket 链路已建立");
    }

    @Override
    public void onMessage(String raw) {
        try {
            JsonNode msg = mapper.readTree(raw);
            String postType = msg.path("post_type").asText("");
            String messageType = msg.path("message_type").asText("");

            // 诊断: 打印非message事件
            if (!"message".equals(postType)) {
                log.debug("[Napcat] 非消息事件: post_type={}, keys={}",
                        postType, msg.fieldNames());
                return;
            }
            String text = msg.path("raw_message").asText("");
            List<String> imageUrls = new ArrayList<>();
            // raw_message 可能为空但 message 字段有内容（Napcat版本差异）
            if (text.isBlank()) {
                MessageParts parts = extractMessage(msg.path("message"));
                text = parts.text;
                imageUrls = parts.imageUrls;
            } else {
                // raw_message 有值时也提取图片 URL
                imageUrls = extractImageUrls(msg.path("message"));
            }
            if (text.isBlank() && imageUrls.isEmpty()) {
                log.debug("[Napcat] 消息事件但文本为空: messageType={}", messageType);
                return;
            }

            // ── 提取发送者信息（纯计算，无I/O，立即完成）──
            JsonNode senderNode = msg.path("sender");
            long senderId = senderNode.path("user_id").asLong();
            boolean isSelf = isSelfId(senderId);

            String nickname = senderNode.path("nickname").asText("");
            String card = senderNode.path("card").asText("");
            String senderName;
            if (isSelf) {
                senderName = "[自己]";
            } else {
                senderName = !card.isBlank() ? card : (!nickname.isBlank() ? nickname : String.valueOf(senderId));
            }

            String atInfo = extractAtInfo(msg.path("message"));

            String role = isSelf ? "self" : ("qqid:" + senderId);

            String source;
            if ("group".equals(messageType)) {
                long groupId = msg.path("group_id").asLong();
                source = "qq_group:" + groupId;
                text = String.format("[source:%s] [role:%s] %s%s",
                        source, role,
                        atInfo.isEmpty() ? "" : atInfo, text);
                log.info("[Napcat] 📨 群聊消息 | 群:{} | 发送者:{}({}) | {}chars | {}张图",
                        groupId, senderName, senderId, text.length(), imageUrls.size());
            } else if ("private".equals(messageType)) {
                long userId = msg.path("user_id").asLong();
                source = "qq_private:" + userId;
                text = String.format("[source:%s] [role:%s] %s%s",
                        source, role,
                        atInfo.isEmpty() ? "" : atInfo, text);
                log.info("[Napcat] 📨 私聊消息 | 发送者:{}({}) | {}chars | {}张图",
                        senderName, userId, text.length(), imageUrls.size());
            } else {
                return;
            }

            // ── 分流：有图片走异步，无图片走同步快速通道 ──
            if (!imageUrls.isEmpty()) {
                final String finalText = text;
                final String finalSource = source;
                final List<String> finalImageUrls = imageUrls;
                imageProcessor.execute(() -> {
                    try {
                        // 1. 视觉识别（异步等待）
                        VisionService vision = VisionService.getInstance();
                        List<String> descriptions = vision.describeImages(finalImageUrls);
                        String mergedText = mergeDescriptions(finalText, descriptions);

                        // 2. 拉取历史记录
                        String historyText = fetchRecentHistory(finalSource);
                        String textWithHistory = historyText.isEmpty()
                                ? mergedText
                                : mergedText + "\n\n【最近聊天记录】\n" + historyText;

                        // 3. 压入 ActionPool
                        if (messageCallback != null) {
                            messageCallback.accept(finalSource, textWithHistory);
                        }
                        log.debug("[Napcat] 📷 图片消息异步处理完成: source={}", finalSource);
                    } catch (Exception e) {
                        log.warn("[Napcat] 图片消息异步处理异常: {}", e.getMessage());
                        // 降级：不带图片描述直接推送
                        String historyText = fetchRecentHistory(finalSource);
                        String textWithHistory = historyText.isEmpty()
                                ? finalText
                                : finalText + "\n\n【最近聊天记录】\n" + historyText;
                        if (messageCallback != null) {
                            messageCallback.accept(finalSource, textWithHistory);
                        }
                    }
                });
                return; // ★ WebSocket 线程立即释放
            }

            // ★ 快速通道：无图片，同步处理
            String historyText = fetchRecentHistory(source);
            String textWithHistory = historyText.isEmpty() ? text : text + "\n\n【最近聊天记录】\n" + historyText;

            if (messageCallback != null) {
                messageCallback.accept(source, textWithHistory);
            }
        } catch (Exception e) {
            log.warn("[Napcat] 消息解析失败: {}", raw.length() > 100 ? raw.substring(0, 100) : raw);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        connected = false;
        log.warn("[Napcat] 🔌 WS连接关闭: code={}, reason={}, remote={}", code, reason, remote);
        scheduleReconnect();
    }

    @Override
    public void onError(Exception ex) {
        if (ex instanceof java.net.ConnectException) {
            log.warn("[Napcat] 无法连接: {}", ex.getMessage());
        } else {
            log.error("[Napcat] WS错误", ex);
        }
        // ★ 仅在连接已断开时触发重连；瞬态错误不误杀存活连接
        if (!isOpen()) {
            connected = false;
            scheduleReconnect();
        }
    }

    // ── HTTP 辅助 ──

    private String httpPost(String endpoint, Map<String, Object> params) {
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
                String respBody = resp.body() != null ? resp.body().string() : "";
                if (!resp.isSuccessful()) {
                    log.warn("[Napcat] HTTP {} {} → {}", resp.code(), endpoint,
                            respBody.length() > 200 ? respBody.substring(0, 200) : respBody);
                }
                return respBody;
            }
        } catch (Exception e) {
            log.error("[Napcat] HTTP POST 异常: {}", endpoint, e);
            return "ERROR: " + e.getMessage();
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

    private boolean isSelfId(long id) {
        return !selfId.isBlank() && String.valueOf(id).equals(selfId);
    }

    // ═══════════════════════════════════════════
    // 历史记录
    // ═══════════════════════════════════════════

    /** 根据source自动拉取最近历史 */
    private String fetchRecentHistory(String source) {
        int count = MKConfig.CHAT_HISTORY_AUTO_COUNT;
        if (count <= 0) return "";

        try {
            java.util.List<String> history;
            if (source.startsWith("qq_group:")) {
                long groupId = Long.parseLong(source.substring("qq_group:".length()));
                history = getGroupHistorySync(groupId, count);
            } else if (source.startsWith("qqid:") || source.startsWith("qq_private:")) {
                int prefix = source.startsWith("qq_private:") ? "qq_private:".length() : "qqid:".length();
                long userId = Long.parseLong(source.substring(prefix));
                history = getFriendHistorySync(userId, count);
            } else {
                return "";
            }
            if (history == null || history.isEmpty()) return "";
            return String.join("\n", history);
        } catch (Exception e) {
            return "";
        }
    }

    public java.util.List<String> getGroupHistorySync(long groupId, int count) {
        java.util.List<String> list = new java.util.ArrayList<>();
        try {
            String url = httpBase + "/get_group_msg_history";
            ObjectNode payload = mapper.createObjectNode()
                    .put("group_id", groupId).put("message_seq", 0).put("count", count);
            String body = httpPostWithBody(url, payload.toString());
            if (body != null && !body.isBlank()) {
                JsonNode root = mapper.readTree(body);
                JsonNode msgs = root.path("data").path("messages");
                if (msgs.isArray()) {
                    for (JsonNode m : msgs) {
                        String name = m.path("sender").path("card").asText("");
                        if (name.isBlank()) name = m.path("sender").path("nickname").asText("");
                        if (name.isBlank()) name = String.valueOf(m.path("user_id").asLong());
                        String text = extractText(m.path("message"));
                        String roleSelf = isSelfId(m.path("user_id").asLong()) ? "self" : ("qqid:" + m.path("user_id").asLong());
                        if (!text.isBlank()) list.add("[source:qq_group:" + groupId + "] [role:" + roleSelf + "] " + name + ": " + text);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[Napcat] 获取群历史失败 group={}", groupId, e);
        }
        return list;
    }

    public java.util.List<String> getFriendHistorySync(long userId, int count) {
        java.util.List<String> list = new java.util.ArrayList<>();
        try {
            String url = httpBase + "/get_friend_msg_history";
            ObjectNode payload = mapper.createObjectNode()
                    .put("user_id", userId).put("message_seq", 0).put("count", count);
            String body = httpPostWithBody(url, payload.toString());
            if (body != null && !body.isBlank()) {
                JsonNode root = mapper.readTree(body);
                JsonNode msgs = root.path("data").path("messages");
                if (msgs.isArray()) {
                    for (JsonNode m : msgs) {
                        String name = m.path("sender").path("nickname").asText("");
                        if (name.isBlank()) name = String.valueOf(m.path("user_id").asLong());
                        String text = extractText(m.path("message"));
                        String roleSelf = isSelfId(m.path("user_id").asLong()) ? "self" : ("qqid:" + m.path("user_id").asLong());
                        if (!text.isBlank()) list.add("[source:qq_private:" + userId + "] [role:" + roleSelf + "] " + name + ": " + text);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[Napcat] 获取私聊历史失败 user={}", userId, e);
        }
        return list;
    }

    /** HTTP POST 返回响应体字符串 */
    private String httpPostWithBody(String url, String jsonBody) {
        try {
            RequestBody reqBody = RequestBody.create(MediaType.parse("application/json"), jsonBody);
            Request.Builder b = new Request.Builder().url(url).post(reqBody);
            if (token != null && !token.isBlank()) b.addHeader("Authorization", "Bearer " + token);
            try (Response resp = httpClient.newCall(b.build()).execute()) {
                if (resp.isSuccessful() && resp.body() != null) return resp.body().string();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 从Napcat消息JSON中提取纯文本和图片URL */
    private record MessageParts(String text, List<String> imageUrls) {}

    private static MessageParts extractMessage(JsonNode messageNode) {
        if (messageNode.isTextual()) {
            return new MessageParts(messageNode.asText(), List.of());
        }
        if (messageNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            List<String> imageUrls = new ArrayList<>();
            for (JsonNode seg : messageNode) {
                String type = seg.path("type").asText("");
                if ("text".equals(type)) {
                    sb.append(seg.path("data").path("text").asText(""));
                } else if ("image".equals(type)) {
                    String url = seg.path("data").path("url").asText("");
                    if (url.isBlank()) url = seg.path("data").path("file").asText("");
                    if (!url.isBlank()) {
                        imageUrls.add(url);
                        sb.append("[图片]");
                    } else {
                        sb.append("[图片]");
                    }
                } else if ("at".equals(type)) {
                    sb.append("@").append(seg.path("data").path("qq").asText(""));
                }
            }
            return new MessageParts(sb.toString(), imageUrls);
        }
        return new MessageParts(messageNode.asText(""), List.of());
    }

    /** 从消息JSON中提取@提及信息 */
    private String extractAtInfo(JsonNode messageNode) {
        if (!messageNode.isArray()) return "";
        java.util.List<String> atTargets = new java.util.ArrayList<>();
        for (JsonNode seg : messageNode) {
            if ("at".equals(seg.path("type").asText(""))) {
                String qq = seg.path("data").path("qq").asText("");
                if (!qq.isBlank()) atTargets.add(qq.equals(selfId) ? "self" : qq);
            }
        }
        if (atTargets.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String qq : atTargets) {
            sb.append("[@role:qqid:").append(qq).append("] ");
        }
        return sb.toString().trim();
    }

    /** 将视觉识别描述合并到消息文本中 */
    private static String mergeDescriptions(String text, List<String> descriptions) {
        if (descriptions.isEmpty()) return text;
        StringBuilder imgDesc = new StringBuilder();
        for (String desc : descriptions) {
            if (!"[图片]".equals(desc)) {
                imgDesc.append(desc);
            }
        }
        if (imgDesc.length() == 0) return text;
        return text.isBlank() ? imgDesc.toString()
                : text + " " + imgDesc.toString();
    }

    /** 从消息JSON中只提取图片URL列表（用于 raw_message 非空但含图的场景） */
    private static List<String> extractImageUrls(JsonNode messageNode) {
        List<String> urls = new ArrayList<>();
        if (!messageNode.isArray()) return urls;
        for (JsonNode seg : messageNode) {
            if ("image".equals(seg.path("type").asText(""))) {
                String url = seg.path("data").path("url").asText("");
                if (url.isBlank()) url = seg.path("data").path("file").asText("");
                if (!url.isBlank()) urls.add(url);
            }
        }
        return urls;
    }

    /** @deprecated 用 extractMessage() 代替，保留兼容 */
    @Deprecated
    private static String extractText(JsonNode messageNode) {
        if (messageNode.isTextual()) return messageNode.asText();
        if (messageNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode seg : messageNode) {
                String type = seg.path("type").asText("");
                if ("text".equals(type)) {
                    sb.append(seg.path("data").path("text").asText(""));
                } else if ("image".equals(type)) {
                    sb.append("[图片]");
                } else if ("at".equals(type)) {
                    sb.append("@").append(seg.path("data").path("qq").asText(""));
                }
            }
            return sb.toString();
        }
        return messageNode.asText("");
    }

    // ═══════════════════════════════════════════
    // 心跳 + 自动重连
    // ═══════════════════════════════════════════

    /** 心跳 ping：每30s发送一次，防止 Napcat 因空闲断开连接 */
    private void sendHeartbeat() {
        if (!isOpen()) return;
        try {
            sendPing();
            log.debug("[Napcat] 💓 心跳 ping 已发送");
        } catch (Exception e) {
            log.debug("[Napcat] 心跳 ping 发送失败: {}", e.getMessage());
        }
    }

    /** 调度重连（指数退避），由 onClose/onError/start 失败时调用 */
    private void scheduleReconnect() {
        if (!shouldReconnect || reconnecting) return;
        // ★ 防重复调度：同一时刻只允许一个待执行的重连任务
        if (reconnectScheduled) return;
        reconnectScheduled = true;

        // ★ 稳定连接判定：如果之前连接保持了足够长时间，重置退避计数
        if (connectedSince > 0) {
            long stableMs = System.currentTimeMillis() - connectedSince;
            if (stableMs >= MIN_STABLE_CONNECT_SEC * 1000L) {
                reconnectAttempts = 0;
                log.debug("[Napcat] 连接已稳定{}秒，重置退避计数", stableMs / 1000);
            }
        }
        connectedSince = 0;

        int shift = Math.min(reconnectAttempts, 6);
        int delaySec = Math.min(INITIAL_RECONNECT_DELAY_SEC * (1 << shift), MAX_RECONNECT_BACKOFF_SEC);
        reconnectAttempts++;
        log.info("[Napcat] 🔄 将在{}秒后重连 (第{}次)", delaySec, reconnectAttempts);
        keepAliveScheduler.schedule(this::doReconnect, delaySec, TimeUnit.SECONDS);
    }

    /** 执行重连 */
    private void doReconnect() {
        reconnectScheduled = false;
        if (!shouldReconnect) return;
        if (isOpen()) {
            reconnecting = false;
            return; // 已经连接（可能被心跳或 onOpen 抢先）
        }
        reconnecting = true;
        try {
            log.info("[Napcat] 🔄 正在重连... (第{}次)", reconnectAttempts);
            reconnectBlocking();
            // onOpen 回调会设置 connected=true, reconnecting=false, connectedSince
            fetchSelfId();

            // ★ 验证连接是否仍存活（Napcat 可能在 onOpen 后立即关闭）
            if (isOpen()) {
                log.info("[Napcat] ✅ 重连成功 (第{}次)", reconnectAttempts);
            } else {
                log.warn("[Napcat] ⚠️ 重连后连接已断开，将再次重连");
                reconnecting = false;
                scheduleReconnect();
            }
        } catch (Exception e) {
            log.warn("[Napcat] ❌ 重连失败 (第{}次): {}", reconnectAttempts, e.getMessage());
            reconnecting = false;
            reconnectScheduled = false;
            scheduleReconnect();
        }
    }

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
