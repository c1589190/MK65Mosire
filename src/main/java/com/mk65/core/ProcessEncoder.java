package com.mk65.core;

import com.mk65.llm.LLMAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Process 编码器。
 *
 * 将 LLM 返回的 tool_calls 编码为行动 token 序列。
 * 只编码工具名和参数键。参数值不编码。
 */
public class ProcessEncoder {

    /**
     * 编码一轮 LLM 的工具调用。
     *
     * @param toolCalls LLM 返回的工具调用列表
     * @return 行动 token 序列，如 ["action:fetch_web", "param:query", "action:send_message", "param:target", "param:messages"]
     */
    public static List<String> encode(List<LLMAdapter.ToolCall> toolCalls) {
        List<String> tokens = new ArrayList<>();
        if (toolCalls == null || toolCalls.isEmpty()) return tokens;

        for (LLMAdapter.ToolCall tc : toolCalls) {
            tokens.add("action:" + tc.name());

            // 提取参数键
            try {
                com.fasterxml.jackson.databind.JsonNode args =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(tc.arguments());
                java.util.Iterator<String> fieldNames = args.fieldNames();
                while (fieldNames.hasNext()) {
                    tokens.add("param:" + fieldNames.next());
                }
            } catch (Exception e) {
                // 解析失败不阻塞，至少已编码了action名
            }
        }
        return tokens;
    }

    /**
     * 编码单个工具调用的文本记录（getTextRecord）。
     * 用于从工具层面额外获取文本描述。
     */
    public static List<String> encodeTextRecord(String textRecord) {
        List<String> tokens = new ArrayList<>();
        if (textRecord == null || textRecord.isBlank()) return tokens;

        // 提取关键信息：工具名 + 操作类型
        String lower = textRecord.toLowerCase();
        if (lower.contains("fetch_web")) tokens.add("action:fetch_web");
        if (lower.contains("send_message")) tokens.add("action:send_message");
        if (lower.contains("list_dir")) tokens.add("action:list_dir");
        if (lower.contains("read_file")) tokens.add("action:read_file");
        if (lower.contains("write_file")) tokens.add("action:write_file");
        if (lower.contains("recall")) tokens.add("action:recall");
        if (lower.contains("create_task")) tokens.add("action:create_task");

        return tokens;
    }
}
