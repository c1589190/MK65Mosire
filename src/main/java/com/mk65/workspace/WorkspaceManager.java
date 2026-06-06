package com.mk65.workspace;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.stream.Collectors;

/**
 * 工作区文件管理器。
 */
public class WorkspaceManager {

    private Path currentDir;

    public WorkspaceManager() {
        this.currentDir = Path.of("workspace").toAbsolutePath();
        try {
            Files.createDirectories(currentDir);
        } catch (IOException ignored) {}
    }

    public String write(String path, String content, boolean append) {
        try {
            Path target = resolve(path);
            Files.createDirectories(target.getParent());
            if (append && Files.exists(target)) {
                Files.writeString(target, content, StandardOpenOption.APPEND);
            } else {
                Files.writeString(target, content);
            }
            return "OK: 写入 " + target + " (" + content.length() + " 字符)";
        } catch (Exception e) {
            return "ERROR: 写入失败 — " + e.getMessage();
        }
    }

    public String read(String path, long chunkId) {
        try {
            Path target = resolve(path);
            if (!Files.exists(target)) return "ERROR: 文件不存在: " + path;
            String content = Files.readString(target);
            int chunkSize = 4000;
            int start = (int) (chunkId * chunkSize);
            if (start >= content.length()) return "ERROR: chunk_id 超出范围";
            int end = Math.min(start + chunkSize, content.length());
            return content.substring(start, end);
        } catch (Exception e) {
            return "ERROR: 读取失败 — " + e.getMessage();
        }
    }

    public String cd(String path) {
        try {
            Path target = resolve(path);
            if (!Files.isDirectory(target)) return "ERROR: 不是目录: " + path;
            currentDir = target.toRealPath();
            return "OK: 当前目录 → " + currentDir;
        } catch (Exception e) {
            return "ERROR: cd 失败 — " + e.getMessage();
        }
    }

    public String ls() {
        try {
            File dir = currentDir.toFile();
            File[] files = dir.listFiles();
            if (files == null || files.length == 0) return "(空目录)";
            StringBuilder sb = new StringBuilder();
            for (File f : files) {
                sb.append(f.isDirectory() ? "[DIR]  " : "[FILE] ");
                sb.append(f.getName());
                if (!f.isDirectory()) sb.append(" (").append(f.length()).append(" bytes)");
                sb.append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "ERROR: ls 失败 — " + e.getMessage();
        }
    }

    private Path resolve(String relativePath) {
        Path p = Path.of(relativePath);
        if (p.isAbsolute()) return p;
        return currentDir.resolve(relativePath).normalize();
    }
}
