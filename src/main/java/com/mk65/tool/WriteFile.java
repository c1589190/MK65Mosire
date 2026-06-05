package com.mk65.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mk65.workspace.WorkspaceManager;

/**
 * 写入文件内容。
 */
public class WriteFile implements MKTool {

    private static final ObjectMapper mapper = new ObjectMapper();
    private String lastRecord = null;
    private final WorkspaceManager workspace;

    public WriteFile(WorkspaceManager workspace) {
        this.workspace = workspace;
    }

    @Override
    public String getName() { return "write_file"; }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ObjectNode fn = tool.putObject("function");
        fn.put("name", getName());
        fn.put("description", "写入/追加文件内容。覆盖模式会替换整个文件，追加模式在文件末尾添加。");

        ObjectNode params = fn.putObject("parameters");
        params.put("type", "object");

        ObjectNode props = params.putObject("properties");
        ObjectNode path = props.putObject("path");
        path.put("type", "string");
        path.put("description", "文件路径（相对于工作区根目录）。");

        ObjectNode content = props.putObject("content");
        content.put("type", "string");
        content.put("description", "要写入的内容。");

        ObjectNode mode = props.putObject("mode");
        mode.put("type", "string");
        mode.putArray("enum").add("overwrite").add("append");
        mode.put("description", "写入模式: overwrite(覆盖,默认) 或 append(追加)。");

        params.putArray("required").add("path").add("content");
        params.put("additionalProperties", false);
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        String path = arguments.path("path").asText("").trim();
        String content = arguments.path("content").asText("");
        String mode = arguments.path("mode").asText("overwrite").trim().toLowerCase();

        if (path.isBlank()) return "ERROR: 文件路径不能为空。";

        boolean append = "append".equals(mode);
        this.lastRecord = (append ? "write_file(append): " : "write_file: ") + path
                + " (" + content.length() + " 字符)";

        return workspace.write(path, content, append);
    }

    @Override
    public String getTextRecord() {
        return lastRecord != null ? lastRecord : "write_file: 未执行";
    }
}
