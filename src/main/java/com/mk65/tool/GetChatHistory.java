package com.mk65.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mk65.adapter.NapcatAdapter;
import com.mk65.config.MKConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 拉取聊天历史记录。
 * 用于LLM需要更多对话上下文时手动调用。
 */
@Slf4j
public class GetChatHistory implements MKTool {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static volatile NapcatAdapter napcat;
    private String lastRecord = null;

    public static void setNapcat(NapcatAdapter adapter) { napcat = adapter; }

    @Override
    public String getName() { return "get_chat_history"; }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ObjectNode fn = tool.putObject("function");
        fn.put("name", getName());
        fn.put("description", "拉取聊天历史记录。当你需要了解之前的对话上下文时使用。target直接复制消息中的[source:...]字段值：qq_group:群号 / qq_private:QQ号。拉取后应分析历史内容并决定是否需要回复或创建后续任务。");

        ObjectNode params = fn.putObject("parameters");
        params.put("type", "object");

        ObjectNode props = params.putObject("properties");

        ObjectNode target = props.putObject("target");
        target.put("type", "string");
        target.put("description", "直接复制消息中的[source:...]字段值。群聊:qq_group:群号, 私聊:qq_private:QQ号。");

        ObjectNode count = props.putObject("count");
        count.put("type", "integer");
        count.put("description", "拉取条数，默认" + MKConfig.CHAT_HISTORY_TOOL_COUNT + "，最大100。");

        params.putArray("required").add("target");
        params.put("additionalProperties", false);
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        String target = arguments.path("target").asText("").trim();
        if (target.isBlank()) return "ERROR: target 不能为空。";

        int count = Math.min(arguments.path("count").asInt(MKConfig.CHAT_HISTORY_TOOL_COUNT), 100);
        this.lastRecord = "get_chat_history: " + target + " (" + count + "条)";

        if (napcat == null) return "ERROR: QQ适配器未连接。";

        try {
            List<String> history;
            if (target.startsWith("qq_group:")) {
                long groupId = Long.parseLong(target.substring("qq_group:".length()));
                history = napcat.getGroupHistorySync(groupId, count);
            } else if (target.startsWith("qqid:") || target.startsWith("qq_private:")) {
                int prefix = target.startsWith("qq_private:") ? "qq_private:".length() : "qqid:".length();
                long userId = Long.parseLong(target.substring(prefix));
                history = napcat.getFriendHistorySync(userId, count);
            } else {
                return "ERROR: target格式错误。支持 qq_group:群号 或 qq_private:QQ号。";
            }

            if (history == null || history.isEmpty()) {
                return "SYSTEM_FEEDBACK: 该目标没有最近历史记录。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("【聊天历史 ").append(target).append(" — ").append(history.size()).append("条】\n");
            for (String line : history) {
                sb.append(line).append("\n");
            }
            return sb.toString().trim();

        } catch (NumberFormatException e) {
            return "ERROR: target中的ID必须是数字。";
        } catch (Exception e) {
            log.error("[GetChatHistory] 拉取失败", e);
            return "ERROR: 拉取历史失败 - " + e.getMessage();
        }
    }

    @Override
    public String getTextRecord() {
        return lastRecord != null ? lastRecord : "get_chat_history: 未执行";
    }
}
