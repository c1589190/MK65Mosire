package com.mk65.tool;

import ch.qos.logback.classic.Level;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mk65.core.RingBufferAppender;

import java.util.List;

/**
 * 查询系统日志。
 * 从 Logback RingBufferAppender 读取最近的控制台/错误信息，供 LLM 自诊断。
 * 所有现有的 log.error/warn/info 自动收录，无需手动埋点。
 */
public class GetSystemLog implements MKTool {

    private static final ObjectMapper mapper = new ObjectMapper();
    private String lastRecord = null;

    @Override
    public String getName() { return "get_system_log"; }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ObjectNode fn = tool.putObject("function");
        fn.put("name", getName());
        fn.put("description", "查看系统控制台日志。当你怀疑之前的操作出错、或需要了解系统状态时调用。支持按级别和关键词筛选。");

        ObjectNode params = fn.putObject("parameters");
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");

        ObjectNode level = props.putObject("level");
        level.put("type", "string");
        level.put("description", "最低日志级别。ERROR(只看错误) / WARN(含警告) / INFO(全部)。默认WARN。");
        level.put("enum", mapper.createArrayNode().add("ERROR").add("WARN").add("INFO"));

        ObjectNode keyword = props.putObject("keyword");
        keyword.put("type", "string");
        keyword.put("description", "筛选关键词，匹配类名或消息内容。如 'LLM' 'ActionLoop' 'Connection'。不填则不过滤。");

        ObjectNode count = props.putObject("count");
        count.put("type", "integer");
        count.put("description", "返回条数，默认20，最大100。");

        params.put("additionalProperties", false);
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        String levelStr = arguments.path("level").asText("WARN").toUpperCase();
        String keyword = arguments.path("keyword").asText("").trim();
        int count = Math.min(arguments.path("count").asInt(20), 100);
        if (keyword.isEmpty()) keyword = null;

        Level minLevel;
        try {
            minLevel = Level.valueOf(levelStr);
        } catch (IllegalArgumentException e) {
            minLevel = Level.WARN;
        }

        RingBufferAppender buffer = RingBufferAppender.getInstance();
        if (buffer == null) {
            return "ERROR: 日志缓冲区未初始化（logback.xml 中缺少 RingBufferAppender 配置）";
        }

        List<RingBufferAppender.Entry> entries = buffer.query(minLevel, keyword, count);

        this.lastRecord = String.format("get_system_log: level=%s keyword=%s → %d条",
                levelStr, keyword != null ? keyword : "*", entries.size());

        if (entries.isEmpty()) {
            return "SYSTEM_FEEDBACK: 没有匹配的日志记录。（缓冲区总量: " + buffer.size() + "条）";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("【系统日志 — ").append(entries.size()).append("条")
          .append(" (总量").append(buffer.size()).append(")")
          .append(" ≥").append(levelStr);
        if (keyword != null) sb.append(" keyword=").append(keyword);
        sb.append("】\n");
        for (RingBufferAppender.Entry e : entries) {
            sb.append(e.toLine()).append("\n");
        }
        return sb.toString().trim();
    }

    @Override
    public String getTextRecord() {
        return lastRecord != null ? lastRecord : "get_system_log: 未执行";
    }
}
