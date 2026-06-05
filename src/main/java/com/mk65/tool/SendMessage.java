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
        fn.put("description", "向目标发送消息。target为'console'时发送到控制台，'qq_group:群号'时发送到QQ群，'qqid:QQ号'时发送到QQ私聊。");

        ObjectNode params = fn.putObject("parameters");
        params.put("type", "object");

        ObjectNode props = params.putObject("properties");

        ObjectNode target = props.putObject("target");
        target.put("type", "string");
        target.put("description", "消息目标。console / qq_group:群号 / qqid:QQ号");

        ObjectNode messages = props.putObject("messages");
        messages.put("type", "array");
        messages.put("description", "分段发送的消息内容数组。每个元素为一条消息文本。");
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
                    res = "ERROR: 无法识别的 target 格式。支持: console, qq_group:群号, qqid:QQ号";
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
            if (target.startsWith("qq_group:")) {
                long groupId = Long.parseLong(target.substring("qq_group:".length()));
                napcat.sendGroupMsg(groupId, msg);
                return "SUCCESS: 消息已发送至群聊 " + groupId;
            } else {
                // qqid: 或 qq_private:
                int prefixLen = target.startsWith("qq_private:") ? "qq_private:".length() : "qqid:".length();
                long userId = Long.parseLong(target.substring(prefixLen));
                napcat.sendPrivateMsg(userId, msg);
                return "SUCCESS: 消息已发送至用户 " + userId;
            }
        } catch (NumberFormatException e) {
            return "ERROR: target格式错误，ID必须是数字。";
        } catch (Exception e) {
            log.error("[SendMessage] QQ发送失败", e);
            return "ERROR: 发送失败 - " + e.getMessage();
        }
    }

    @Override
    public String getTextRecord() {
        if (lastTarget == null) return "send_message: 未执行";
        return "send_message: 向 " + lastTarget + " 发送了消息: " + lastContent;
    }
}
