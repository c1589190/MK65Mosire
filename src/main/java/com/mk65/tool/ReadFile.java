package com.mk65.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mk65.workspace.WorkspaceManager;

/**
 * 读取文件内容。
 */
public class ReadFile implements MKTool {

    private static final ObjectMapper mapper = new ObjectMapper();
    private String lastRecord = null;
    private final WorkspaceManager workspace;

    public ReadFile(WorkspaceManager workspace) {
        this.workspace = workspace;
    }

    @Override
    public String getName() { return "read_file"; }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ObjectNode fn = tool.putObject("function");
        fn.put("name", getName());
        fn.put("description", "读取文件内容。支持大文件分块读取。");

        ObjectNode params = fn.putObject("parameters");
        params.put("type", "object");

        ObjectNode props = params.putObject("properties");
        ObjectNode path = props.putObject("path");
        path.put("type", "string");
        path.put("description", "文件路径（相对于工作区根目录）。");

        ObjectNode chunk = props.putObject("chunk_id");
        chunk.put("type", "integer");
        chunk.put("description", "分块ID，从0开始。文件较大时使用，不填则读取全部。");

        params.putArray("required").add("path");
        params.put("additionalProperties", false);
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        String path = arguments.path("path").asText("").trim();
        if (path.isBlank()) return "ERROR: 文件路径不能为空。";

        long chunkId = arguments.path("chunk_id").asLong(0);
        this.lastRecord = "read_file: " + path + (chunkId > 0 ? " (chunk " + chunkId + ")" : "");

        return workspace.read(path, chunkId);
    }

    @Override
    public String getTextRecord() {
        return lastRecord != null ? lastRecord : "read_file: 未执行";
    }
}
