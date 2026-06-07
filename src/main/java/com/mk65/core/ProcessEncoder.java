package com.mk65.core;

import com.mk65.llm.LLMAdapter;
import com.mk65.tokenizer.Tokenizer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Process 编码器。
 *
 * 将 LLM 返回的 tool_calls 编码为行动 token 序列，分三类：
 * 1. action:工具名    — 调了什么工具
 * 2. param:参数键     — 用了什么参数
 * 3. value:分词片段   — 具体说了什么话、搜了什么东西
 */
public class ProcessEncoder {

    private static final com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** 标识符类参数名 — 值不拆分，整体保留为一个 token */
    private static final Set<String> ID_PARAMS = Set.of(
            "source", "target", "path", "mode"
    );

    /**
     * 编码一轮 LLM 的工具调用。
     */
    public static List<String> encode(List<LLMAdapter.ToolCall> toolCalls) {
        List<String> tokens = new ArrayList<>();
        if (toolCalls == null || toolCalls.isEmpty()) return tokens;

        Tokenizer tokenizer = Tokenizer.getInstance();

        for (LLMAdapter.ToolCall tc : toolCalls) {
            tokens.add("action:" + tc.name());

            try {
                com.fasterxml.jackson.databind.JsonNode args = mapper.readTree(tc.arguments());
                Iterator<String> fieldNames = args.fieldNames();
                while (fieldNames.hasNext()) {
                    String key = fieldNames.next();
                    tokens.add("param:" + key);

                    com.fasterxml.jackson.databind.JsonNode val = args.get(key);
                    if (ID_PARAMS.contains(key)) {
                        // 标识符参数（source/target/path/mode）：整体保留，不拆分
                        List<String> rawValues = extractRawValues(val);
                        for (String rv : rawValues) {
                            tokens.add("value:" + rv);
                        }
                    } else {
                        // 内容参数：走 Jieba 分词
                        List<String> valueTokens = extractValueTokens(val, tokenizer);
                        for (String vt : valueTokens) {
                            tokens.add("value:" + vt);
                        }
                    }
                }
            } catch (Exception e) {
                // JSON解析失败不阻塞
            }
        }
        return tokens;
    }

    /**
     * 递归提取标识符类参数值，不拆分。
     * 支持 string / array 两种 JSON 类型。
     */
    private static List<String> extractRawValues(
            com.fasterxml.jackson.databind.JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node == null || node.isNull()) return result;

        if (node.isTextual()) {
            String text = node.asText().trim().toLowerCase();
            if (!text.isBlank()) result.add(text);
        } else if (node.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode item : node) {
                result.addAll(extractRawValues(item));
            }
        }
        return result;
    }

    /**
     * 递归提取参数值中的所有文本片段并分词。
     * 支持 string / array / object 三种 JSON 类型。
     */
    private static List<String> extractValueTokens(
            com.fasterxml.jackson.databind.JsonNode node, Tokenizer tokenizer) {
        List<String> result = new ArrayList<>();
        if (node == null || node.isNull()) return result;

        if (node.isTextual()) {
            String text = node.asText();
            if (!text.isBlank()) {
                result.addAll(tokenizer.segment(text));
            }
        } else if (node.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode item : node) {
                result.addAll(extractValueTokens(item, tokenizer));
            }
        } else if (node.isObject()) {
            // 对象的值继续递归，键忽略（键=param已经记录）
            Iterator<String> fields = node.fieldNames();
            while (fields.hasNext()) {
                result.addAll(extractValueTokens(node.get(fields.next()), tokenizer));
            }
        }
        // number/boolean 忽略 — 不产生有意义的文本token
        return result;
    }

    /**
     * 编码工具文本记录（getTextRecord）。
     * 只提取工具名标记，具体内容已在 encode() 的 value 分词中覆盖。
     */
    public static List<String> encodeTextRecord(String textRecord) {
        List<String> tokens = new ArrayList<>();
        if (textRecord == null || textRecord.isBlank()) return tokens;

        String lower = textRecord.toLowerCase();
        if (lower.contains("fetch_web")) tokens.add("action:fetch_web");
        if (lower.contains("send_message")) tokens.add("action:send_message");
        if (lower.contains("list_dir")) tokens.add("action:list_dir");
        if (lower.contains("read_file")) tokens.add("action:read_file");
        if (lower.contains("write_file")) tokens.add("action:write_file");
        if (lower.contains("recall")) tokens.add("action:recall");
        if (lower.contains("create_task")) tokens.add("action:create_task");
        if (lower.contains("finish_action")) tokens.add("action:finish_action");
        if (lower.contains("get_chat_history")) tokens.add("action:get_chat_history");

        return tokens;
    }
}
