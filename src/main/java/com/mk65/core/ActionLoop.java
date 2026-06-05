package com.mk65.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.mk65.config.MKConfig;
import com.mk65.core.PrepareActionPool.ActionInput;
import com.mk65.llm.LLMAdapter;
import com.mk65.llm.LLMAdapter.LLMResult;
import com.mk65.llm.LLMAdapter.ToolCall;
import com.mk65.motivation.ConflictDetector;
import com.mk65.motivation.MemoryManager;
import com.mk65.motivation.MotivationMatrix;
import com.mk65.motivation.MotivationReport;
import com.mk65.tokenizer.Tokenizer;
import com.mk65.tool.MKTool;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MK65 主编排器。
 *
 * 核心循环：
 * 1. 等待输入
 * 2. 分词 → 动机查询 → 生成报告
 * 3. 构建 prompt → 调用 LLM
 * 4. 执行工具 → 编码 Process token
 * 5. 更新共现矩阵
 * 6. 回到 1
 */
@Slf4j
public class ActionLoop {

    private static volatile ActionLoop INSTANCE;

    private final PrepareActionPool actionPool;
    private final Tokenizer tokenizer;
    private final MotivationMatrix matrix;
    private final LLMAdapter llm;
    private final Map<String, MKTool> toolbox;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService executor;
    private final AtomicBoolean isProcessing;
    private final AtomicInteger roundCount;
    private String systemPrompt;

    private ActionLoop() {
        this.actionPool = new PrepareActionPool();
        this.tokenizer = Tokenizer.getInstance();
        this.matrix = MotivationMatrix.getInstance();
        this.llm = new LLMAdapter();
        this.toolbox = new ConcurrentHashMap<>();
        this.mapper = new ObjectMapper();

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MK65-Scheduler");
            t.setDaemon(true);
            return t;
        });
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MK65-Executor");
            t.setDaemon(true);
            return t;
        });
        this.isProcessing = new AtomicBoolean(false);
        this.roundCount = new AtomicInteger(0);

        initToolbox();
        loadSystemPrompt();
    }

    public static ActionLoop getInstance() {
        if (INSTANCE == null) {
            synchronized (ActionLoop.class) {
                if (INSTANCE == null) {
                    INSTANCE = new ActionLoop();
                }
            }
        }
        return INSTANCE;
    }

    // ==========================================
    // 工具箱
    // ==========================================

    private void initToolbox() {
        var workspace = new com.mk65.workspace.WorkspaceManager();
        registerTool(new com.mk65.tool.FetchWeb());
        registerTool(new com.mk65.tool.SendMessage());
        registerTool(new com.mk65.tool.ListDir(workspace));
        registerTool(new com.mk65.tool.ReadFile(workspace));
        registerTool(new com.mk65.tool.WriteFile(workspace));
        registerTool(new com.mk65.tool.Recall());
        registerTool(new com.mk65.tool.CreateTask());
        com.mk65.tool.CreateTask.setActionPool(actionPool);
        log.info("[ActionLoop] 工具箱已就绪: {} 个工具", toolbox.size());
        toolbox.keySet().forEach(n -> log.info("[ActionLoop]   - {}", n));
    }

    public void registerTool(MKTool tool) {
        if (tool != null && tool.getName() != null) {
            toolbox.put(tool.getName(), tool);
        }
    }

    // ==========================================
    // 公开方法
    // ==========================================

    /** 获取行动池（供 NapcatAdapter / Console 推送消息） */
    public PrepareActionPool getActionPool() {
        return actionPool;
    }

    /** 获取工具箱（供外部查询） */
    public Map<String, MKTool> getToolbox() {
        return toolbox;
    }

    /** 获取轮次计数 */
    public int getRoundCount() {
        return roundCount.get();
    }

    // ==========================================
    // 生命周期
    // ==========================================

    public void start() {
        log.info("[ActionLoop] 🚀 MK65 认知循环启动 — tick={}ms", MKConfig.CORE_TICK_MS);

        // 定期真空清理（每1000轮）
        scheduler.scheduleAtFixedRate(
                matrix::vacuum, 600, 600, TimeUnit.SECONDS);

        // 内心跳循环
        scheduler.scheduleAtFixedRate(
                this::heartbeat, 0, MKConfig.CORE_TICK_MS, TimeUnit.MILLISECONDS);

        log.info("[ActionLoop] ✅ 已启动");
    }

    public void stop() {
        log.info("[ActionLoop] ⏹️ 正在停止... (总轮次: {})", roundCount.get());
        scheduler.shutdown();
        executor.shutdown();
        try {
            scheduler.awaitTermination(3, TimeUnit.SECONDS);
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        com.mk65.config.MKDB.shutdown();
        log.info("[ActionLoop] ✅ 已停止");
    }

    // ==========================================
    // 心跳
    // ==========================================

    private void heartbeat() {
        if (isProcessing.get()) return;

        try {
            PrepareActionPool.ActionInput input = actionPool.poll(100);
            if (input == null) return;

            if (isProcessing.compareAndSet(false, true)) {
                PrepareActionPool.ActionInput finalInput = input;
                executor.submit(() -> {
                    try {
                        processRound(finalInput);
                    } finally {
                        isProcessing.set(false);
                    }
                });
            } else {
                // 重新入池
                actionPool.push(input);
            }
        } catch (Exception e) {
            log.error("[ActionLoop] 心跳异常", e);
            isProcessing.set(false);
        }
    }

    // ==========================================
    // 单轮处理
    // ==========================================

    private void processRound(ActionInput input) {
        int round = roundCount.incrementAndGet();
        log.info("[ActionLoop] ⏰ 第{}轮 — 来源={}, 文本={}",
                round, input.source(),
                input.rawText().length() > 60
                        ? input.rawText().substring(0, 60) + "..."
                        : input.rawText());

        try {
            // ── 1. 分词 ──
            List<String> inputTokens = tokenizer.segment(input.actionText());
            log.debug("[ActionLoop] 分词结果: {}", inputTokens);

            // ── 2. 动机查询 ──
            Map<String, Map<String, Double>> dist = matrix.queryActionDistribution(inputTokens);
            Set<String> novelTokens = matrix.findNovelTokens(inputTokens);
            List<ConflictDetector.ConflictPair> conflicts = ConflictDetector.detectConflicts(dist);

            // ★ 自动经验检索：Jaccard匹配最近500条经验
            MemoryManager mem = MemoryManager.getInstance();
            java.util.List<MemoryManager.ExpMatch> autoMemories = mem.autoRecall(inputTokens, 3);

            // 综合投票
            Map<String, Double> overallVotes = computeOverallVotes(dist);

            boolean hasHistory = !dist.isEmpty() || !autoMemories.isEmpty();

            MotivationReport report = new MotivationReport(
                    overallVotes, dist, conflicts, novelTokens, autoMemories, hasHistory);

            // ── 3. 构建 prompt ──
            String userMessage = buildUserMessage(input.actionText(), report);
            ArrayNode toolsArray = buildToolsArray();

            // ── 4. 调用 LLM ──
            log.info("[ActionLoop] 🤖 调用LLM (userMessage={}chars, tools={}, globalMessages={})",
                    userMessage.length(), toolsArray.size(), llm.getMessageCount());
            LLMResult result = llm.chat(userMessage, toolsArray);

            if (result.isError()) {
                log.error("[ActionLoop] ❌ LLM 返回错误: {}", result.content());
                return;
            }

            // ── 5. 执行工具 ──
            List<String> allActionTokens = new ArrayList<>();
            List<String> toolRecords = new ArrayList<>();

            if (result.hasToolCalls()) {
                for (ToolCall tc : result.toolCalls()) {
                    String fnName = tc.name();
                    String fnArgs = tc.arguments();
                    MKTool tool = toolbox.get(fnName);

                    log.info("[ActionLoop]   ▶ 执行: {} (argsLen={})", fnName, fnArgs.length());

                    if (tool != null) {
                        try {
                            JsonNode argsNode = mapper.readTree(fnArgs);
                            long toolStart = System.currentTimeMillis();
                            String execResult = tool.execute(argsNode);
                            long toolMs = System.currentTimeMillis() - toolStart;

                            toolRecords.add("[" + fnName + "]: " + execResult);
                            // ★ 工具结果回灌到 LLM 全局上下文
                            llm.appendToolResult(tc.id(), fnName, execResult);

                            log.info("[ActionLoop]   ✅ {} ({}ms): {}",
                                    fnName, toolMs,
                                    execResult.length() > 80
                                            ? execResult.substring(0, 80) + "..."
                                            : execResult);
                        } catch (Exception e) {
                            String errMsg = "[" + fnName + "] 执行异常: " + e.getMessage();
                            toolRecords.add(errMsg);
                            llm.appendToolResult(tc.id(), fnName, errMsg);
                            log.error("[ActionLoop]   ❌ {} 执行异常", fnName, e);
                        }
                    } else {
                        String errMsg = "工具 \"" + fnName + "\" 未注册";
                        toolRecords.add(errMsg);
                        llm.appendToolResult(tc.id() != null ? tc.id() : "unknown", fnName, errMsg);
                        log.warn("[ActionLoop]   ⚠️ {}", errMsg);
                    }
                }

                // 编码行动token
                allActionTokens.addAll(ProcessEncoder.encode(result.toolCalls()));

                // 也加入工具记录的编码
                for (String record : toolRecords) {
                    allActionTokens.addAll(ProcessEncoder.encodeTextRecord(record));
                }
            } else {
                // LLM 纯文本响应（没有工具调用）
                log.info("[ActionLoop] LLM 纯文本响应: {}chars", result.content().length());
            }

            // ── 6. 更新共现矩阵 ──
            if (!inputTokens.isEmpty() && !allActionTokens.isEmpty()) {
                List<String> uniqueInput = inputTokens.stream().distinct().toList();
                List<String> uniqueAction = allActionTokens.stream().distinct().toList();
                matrix.update(uniqueInput, uniqueAction);
            }

            // ── 6.5 自动录制经验 ──
            List<String> executedToolNames = result.hasToolCalls()
                    ? result.toolCalls().stream().map(ToolCall::name).toList()
                    : List.of();
            String thoughts = result.content() != null ? result.content() : "";
            mem.record(round, input.actionText(), input.source(),
                    thoughts, executedToolNames, toolRecords,
                    inputTokens, allActionTokens);

            // ── 7. 输出控制台反馈 ──
            if (!toolRecords.isEmpty()) {
                log.info("[ActionLoop] 📋 第{}轮工具执行汇总:\n  {}",
                        round, String.join("\n  ", toolRecords));
            }

            log.info("[ActionLoop] ✅ 第{}轮完成 — 输入token={}, 行动token={}, 矩阵轮次={}",
                    round, inputTokens.size(), allActionTokens.size(), matrix.getCurrentRound());

        } catch (Exception e) {
            log.error("[ActionLoop] ❌ 第{}轮处理异常", round, e);
        }
    }

    // ==========================================
    // Prompt 构建
    // ==========================================

    private void loadSystemPrompt() {
        try {
            java.nio.file.Path promptFile = java.nio.file.Path.of("prompts", "SYSTEM.md");
            if (java.nio.file.Files.exists(promptFile)) {
                this.systemPrompt = java.nio.file.Files.readString(promptFile);
            } else {
                this.systemPrompt = buildDefaultSystemPrompt();
            }
        } catch (Exception e) {
            this.systemPrompt = buildDefaultSystemPrompt();
        }
        // ★ 注入 LLM 全局缓存（前缀不变 → API层缓存命中）
        llm.setSystemPrompt(systemPrompt);
        log.info("[ActionLoop] 系统指令已加载 ({}chars) 并注入LLM全局缓存", systemPrompt.length());
    }

    private String buildDefaultSystemPrompt() {
        return """
                你是一个运行在 MK65 认知架构中的 AI Agent。

                每轮你会收到：
                1. 当前需要处理的信息（ActionText）
                2. 动机报告 — 白箱统计系统从你的实践历史中自动生成的注意力引导数据

                动机报告说明：
                - 综合行动倾向：当前输入中各 token 在历史上指向的行动方向
                - 输入内部冲突：输入中两个 token 的行动方向互相矛盾
                - 新异 token：首次出现的 token，无历史参考

                重要：动机报告是统计参考，不是命令。你可以选择遵循它，也可以基于你的推理选择不同的行动方案。

                你的输出应为 JSON 格式：
                {
                  "thoughts": "你的推理过程",
                  "tool_calls": [...]
                }

                如果没有需要调用的工具，tool_calls 为空数组。

                实是求是。逻辑完备。行动规范。
                """;
    }

    private String buildUserMessage(String actionText, MotivationReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append("【当前输入】\n");
        sb.append(actionText).append("\n\n");
        sb.append(report.toPromptBlock());
        return sb.toString();
    }

    private ArrayNode buildToolsArray() {
        ArrayNode tools = mapper.createArrayNode();
        for (MKTool tool : toolbox.values()) {
            if (tool.isAutoLoad()) {
                tools.add(tool.getToolDefinition());
            }
        }
        return tools;
    }

    // ==========================================
    // 辅助
    // ==========================================

    private Map<String, Double> computeOverallVotes(Map<String, Map<String, Double>> perTokenDist) {
        Map<String, Double> overall = new HashMap<>();
        for (Map<String, Double> dist : perTokenDist.values()) {
            double total = dist.values().stream().mapToDouble(Double::doubleValue).sum();
            if (total == 0) continue;
            for (Map.Entry<String, Double> e : dist.entrySet()) {
                overall.merge(e.getKey(), e.getValue() / total, Double::sum);
            }
        }
        return overall;
    }
}
