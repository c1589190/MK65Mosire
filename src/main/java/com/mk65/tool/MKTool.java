package com.mk65.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * MK65 工具接口。
 * 所有工具必须实现此接口。
 */
public interface MKTool {

    /** 工具唯一名称，对应 LLM function calling 的 name */
    String getName();

    /** 生成 OpenAI function-calling JSON Schema */
    ObjectNode getToolDefinition();

    /** 执行工具逻辑。参数是 LLM 填好的 JSON。返回执行结果文本。 */
    String execute(JsonNode arguments);

    /**
     * 返回本工具本次执行的文本描述。
     * 这个描述会被 ProcessEncoder 编码为行动token序列，用于动机模型更新。
     */
    String getTextRecord();

    /** 是否默认加载到工具箱 */
    default boolean isAutoLoad() { return true; }
}
