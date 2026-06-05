package com.mk65.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mk65.workspace.WorkspaceManager;

/**
 * 列出目录内容。
 */
public class ListDir implements MKTool {

    private static final ObjectMapper mapper = new ObjectMapper();
    private String lastRecord = null;
    private final WorkspaceManager workspace;

    public ListDir(WorkspaceManager workspace) {
        this.workspace = workspace;
    }

    @Override
    public String getName() { return "list_dir"; }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ObjectNode fn = tool.putObject("function");
        fn.put("name", getName());
        fn.put("description", "列出指定目录下的文件和子目录。不指定路径则列出当前工作目录。");

        ObjectNode params = fn.putObject("parameters");
        params.put("type", "object");

        ObjectNode props = params.putObject("properties");
        ObjectNode path = props.putObject("path");
        path.put("type", "string");
        path.put("description", "目录路径（相对于工作区根目录）。不填则列出当前目录。");

        params.put("additionalProperties", false);
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        String path = arguments.path("path").asText("").trim();
        this.lastRecord = path.isBlank() ? "list_dir: 当前目录" : "list_dir: " + path;

        if (!path.isBlank()) {
            String cdRes = workspace.cd(path);
            if (cdRes.startsWith("ERROR")) return cdRes;
        }
        return workspace.ls();
    }

    @Override
    public String getTextRecord() {
        return lastRecord != null ? lastRecord : "list_dir: 未执行";
    }
}
