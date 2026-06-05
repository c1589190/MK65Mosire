package com.mk65.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mk65.core.PrepareActionPool;

/**
 * 创建后续任务工具。
 * LLM 在当前轮无法完成所有事情时，给自己创建一个待处理任务。
 * 任务进入 PrepareActionPool，按优先级和时间排序。
 */
public class CreateTask implements MKTool {

    private static final ObjectMapper mapper = new ObjectMapper();
    private String lastRecord = null;

    private static volatile PrepareActionPool actionPool;

    public static void setActionPool(PrepareActionPool pool) {
        actionPool = pool;
    }

    @Override
    public String getName() { return "create_task"; }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ObjectNode fn = tool.putObject("function");
        fn.put("name", getName());
        fn.put("description", """
            为自己创建一个后续待处理任务。当你判断当前轮无法完成所有事情，
            或者需要在未来某个时机继续跟进的，使用此工具。
            创建的任务会在后续轮次中作为ActionText被处理。""");

        ObjectNode params = fn.putObject("parameters");
        params.put("type", "object");

        ObjectNode props = params.putObject("properties");

        ObjectNode description = props.putObject("description");
        description.put("type", "string");
        description.put("description", "任务描述。写清楚需要做什么、为什么需要后续处理。这句话会直接成为后续轮的ActionText。");

        ObjectNode priority = props.putObject("priority");
        priority.put("type", "number");
        priority.put("description", "优先级 0.0~1.0。默认0.3。紧急设为0.8以上。");

        ObjectNode source = props.putObject("source");
        source.put("type", "string");
        source.put("description", "关联来源标识。如 qqid:xxx 或 qq_group:xxx。不填默认为 internal。");

        params.putArray("required").add("description");
        params.put("additionalProperties", false);
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        String desc = arguments.path("description").asText("").trim();
        if (desc.isBlank()) return "ERROR: description 不能为空。";

        double pri = arguments.path("priority").asDouble(0.3);
        pri = Math.max(0.0, Math.min(1.0, pri));

        String src = arguments.path("source").asText("internal").trim();
        if (src.isBlank()) src = "internal";

        this.lastRecord = "create_task: [" + desc.substring(0, Math.min(60, desc.length())) + "] pri=" + pri;

        if (actionPool != null) {
            actionPool.pushInternal(desc, pri);
            return "SUCCESS: 任务已创建 — " + desc;
        }
        return "ERROR: 行动池未初始化";
    }

    @Override
    public String getTextRecord() {
        return lastRecord != null ? lastRecord : "create_task: 未执行";
    }
}
