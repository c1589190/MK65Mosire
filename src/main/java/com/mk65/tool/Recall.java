package com.mk65.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mk65.motivation.MemoryManager;
import com.mk65.motivation.MemoryManager.ExpMatch;

import java.util.List;

/**
 * 经验回想工具。
 * LLM 评估当前情境需要更多上下文时主动调用，用关键词搜索历史经验库。
 */
public class Recall implements MKTool {

    private static final ObjectMapper mapper = new ObjectMapper();
    private final MemoryManager memory;
    private String lastRecord = null;

    public Recall() {
        this.memory = MemoryManager.getInstance();
    }

    @Override
    public String getName() { return "recall"; }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ObjectNode fn = tool.putObject("function");
        fn.put("name", getName());
        fn.put("description", "搜索历史经验库。当你需要回忆之前和某个用户说过什么、做过什么决策、或者当前情境有历史先例时使用。");

        ObjectNode params = fn.putObject("parameters");
        params.put("type", "object");

        ObjectNode props = params.putObject("properties");

        ObjectNode query = props.putObject("query");
        query.put("type", "string");
        query.put("description", "搜索关键词。用空格或逗号分隔多个词。例如: \"constantine 偏好 emoji\" 或 \"天气查询模式\"");

        ObjectNode limit = props.putObject("limit");
        limit.put("type", "integer");
        limit.put("description", "最多返回条数，默认5，最大10。");

        params.putArray("required").add("query");
        params.put("additionalProperties", false);
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        String query = arguments.path("query").asText("").trim();
        if (query.isBlank()) return "ERROR: query 不能为空。";

        int limit = Math.min(Math.max(arguments.path("limit").asInt(5), 1), 10);
        this.lastRecord = "recall: 搜索 [" + query + "]";

        List<ExpMatch> results = memory.searchByKeywords(query, limit);
        if (results.isEmpty()) {
            return "【记忆查询结果】关键词: " + query + "\n（未找到相关经验）";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【记忆查询结果】关键词: ").append(query).append(" (").append(results.size()).append("条)\n\n");
        for (ExpMatch m : results) {
            sb.append(m.toPromptLine()).append("\n");
        }
        return sb.toString().trim();
    }

    @Override
    public String getTextRecord() {
        return lastRecord != null ? lastRecord : "recall: 未执行";
    }
}
