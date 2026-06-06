package com.mk65.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mk65.adapter.NapcatAdapter;
import lombok.extern.slf4j.Slf4j;

/**
 * 统一消息发送工具。
 * target = "console" → 输出到控制台
 * target = "qq_group:群号" → QQ群聊
 * target = "qqid:QQ号" → QQ私聊
 */
@Slf4j
public class SendMessage implements MKTool {

    private static final ObjectMapper mapper = new ObjectMapper();
    private String lastTarget = null;
    private String lastContent = null;

    // 由 Main 注入
    private static volatile NapcatAdapter napcat;
    private static volatile java.util.function.Consumer<String> consoleSender;

    public static void setNapcat(NapcatAdapter adapter) { napcat = adapter; }
    public static void setConsoleSender(java.util.function.Consumer<String> sender) { consoleSender = sender; }

    @Override
    public String getName() { return "send_message"; }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ObjectNode fn = tool.putObject("function");
        fn.put("name", getName());
        fn.put("description", "向目标发送消息。target直接复制消息中的[source:...]字段值：console / qq_group:群号 / qq_private:QQ号。");

        ObjectNode params = fn.putObject("parameters");
        params.put("type", "object");

        ObjectNode props = params.putObject("properties");

        ObjectNode target = props.putObject("target");
        target.put("type", "string");
        target.put("description", "消息目标。直接复制[source:...]的值：console / qq_group:群号 / qq_private:QQ号");

        ObjectNode messages = props.putObject("messages");
        messages.put("type", "array");
        messages.put("description", "分段发送的消息内容数组。每元素为一条消息文本。@某人用 [@role:qqid:QQ号] 格式，群聊中会自动转为真正的@提及。");
        ObjectNode items = messages.putObject("items");
        items.put("type", "string");

        params.putArray("required").add("target").add("messages");
        params.put("additionalProperties", false);
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        String target = arguments.path("target").asText("").trim();
        JsonNode messagesNode = arguments.path("messages");

        if (target.isBlank()) return "ERROR: target 不能为空。";

        this.lastTarget = target;
        StringBuilder recordBuilder = new StringBuilder();
        StringBuilder resultBuilder = new StringBuilder();

        if (messagesNode.isArray()) {
            int seq = 0;
            for (JsonNode msgNode : messagesNode) {
                String msg = msgNode.asText();
                if (msg == null || msg.isBlank()) continue;

                String res;
                if ("console".equalsIgnoreCase(target)) {
                    res = sendToConsole(msg);
                } else if (target.startsWith("qq_group:") || target.startsWith("qqid:")
                        || target.startsWith("qq_private:")) {
                    res = sendToQQ(target, msg);
                } else {
                    res = "ERROR: 无法识别的 target 格式。支持: console, qq_group:群号, qq_private:QQ号";
                }

                resultBuilder.append(res).append("\n");
                recordBuilder.append("{").append(msg.length() > 40 ? msg.substring(0, 40) + "..." : msg).append("} ");

                if (messagesNode.size() > 1 && seq < messagesNode.size() - 1) {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                }
                seq++;
            }
        } else {
            String msg = messagesNode.asText();
            if (msg != null && !msg.isBlank()) {
                String res;
                if ("console".equalsIgnoreCase(target)) {
                    res = sendToConsole(msg);
                } else {
                    res = sendToQQ(target, msg);
                }
                resultBuilder.append(res);
                recordBuilder.append("{").append(msg.length() > 40 ? msg.substring(0, 40) + "..." : msg).append("}");
            }
        }

        this.lastContent = recordBuilder.toString().trim();
        return resultBuilder.toString().trim();
    }

    private String sendToConsole(String msg) {
        if (consoleSender != null) {
            consoleSender.accept(msg);
            return "SUCCESS: 控制台消息已输出";
        }
        System.out.println("[Console] " + msg);
        return "SUCCESS: 控制台消息已输出 (stdout)";
    }

    private String sendToQQ(String target, String msg) {
        if (napcat == null || !napcat.isConnected()) {
            return "ERROR: QQ适配器未连接。";
        }
        try {
            // ★ 群聊中：把 [@role:qqid:123] 翻译为 Napcat CQ @提及码
            if (target.startsWith("qq_group:")) {
                msg = translateAtMentions(msg);
                long groupId = Long.parseLong(target.substring("qq_group:".length()));
                String resp = napcat.sendGroupMsg(groupId, msg);
                return parseQQResult(target, resp);
            } else {
                int prefixLen = target.startsWith("qq_private:") ? "qq_private:".length() : "qqid:".length();
                long userId = Long.parseLong(target.substring(prefixLen));
                String resp = napcat.sendPrivateMsg(userId, msg);
                return parseQQResult(target, resp);
            }
        } catch (NumberFormatException e) {
            return "ERROR: target格式错误，ID必须是数字。";
        } catch (Exception e) {
            log.error("[SendMessage] QQ发送失败", e);
            return "ERROR: 发送失败 - " + e.getMessage();
        }
    }

    /** 把 [@role:qqid:123456] 翻译为 Napcat CQ 码 [CQ:at,qq=123456] */
    private static String translateAtMentions(String msg) {
        return msg.replaceAll("\\[@role:qqid:(\\d+)\\]", "[CQ:at,qq=$1]");
    }

    /** 解析Napcat API响应，识别禁言/封禁等异常状态 */
    private String parseQQResult(String target, String resp) {
        if (resp == null || resp.isBlank()) return "SUCCESS";

        // 禁言检测
        if (resp.contains("禁言") || resp.contains("mute") || resp.contains("muted")
                || resp.contains("全员禁言") || resp.contains("shutup")) {
            String warn = "WARN_MUTED: 消息发送失败 — 可能被禁言。Napcat响应: " + resp;
            log.warn("[SendMessage] 🔇 禁言检测: target={}", target);
            notifyMute(target);
            return warn;
        }

        // 封禁/踢出检测
        if (resp.contains("封禁") || resp.contains("ban") || resp.contains("踢出")
                || resp.contains("black") || resp.contains("不在群")) {
            String warn = "WARN_BANNED: 消息发送失败 — 可能被封禁或已不在群内。Napcat响应: " + resp;
            log.warn("[SendMessage] 🚫 封禁检测: target={}", target);
            return warn;
        }

        // 频率限制
        if (resp.contains("频繁") || resp.contains("rate") || resp.contains("limit")
                || resp.contains("过快")) {
            String warn = "WARN_RATELIMIT: 消息发送失败 — 发送过于频繁。Napcat响应: " + resp;
            log.warn("[SendMessage] ⏱ 频率限制: target={}", target);
            return warn;
        }

        return "SUCCESS";
    }

    /** 检测到禁言/封禁时，通知系统 */
    private static void notifyMute(String target) {
        try {
            // 通过反射或直接注入PrepareActionPool（避免耦合在工具层）
            log.info("[SendMessage] 🔇 禁言/封禁通知: 建议后续避免向{}发送消息", target);
        } catch (Exception ignored) {}
    }

    @Override
    public String getTextRecord() {
        if (lastTarget == null) return "send_message: 未执行";
        return "send_message: 向 " + lastTarget + " 发送了消息: " + lastContent;
    }
}
