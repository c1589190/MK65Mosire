package com.mk65.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.mk65.config.MKConfig;
import com.mk65.core.PrepareActionPool.ActionInput;
import com.mk65.llm.LLMAdapter;
import com.mk65.llm.LLMAdapter.LLMResult;
import com.mk65.llm.LLMAdapter.ToolCall;
import com.mk65.config.MKConfig;
import com.mk65.motivation.ConflictDetector;
import com.mk65.motivation.MemoryManager;
import com.mk65.motivation.MotivationMatrix;
import com.mk65.motivation.MotivationReport;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;
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
    private final Configuration freemarker;

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

        // FreeMarker
        this.freemarker = new Configuration(Configuration.VERSION_2_3_33);
        try {
            freemarker.setDirectoryForTemplateLoading(new java.io.File("prompts"));
            freemarker.setDefaultEncoding("UTF-8");
            freemarker.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        } catch (Exception e) {
            log.warn("[ActionLoop] FreeMarker初始化失败，使用默认提示词: {}", e.getMessage());
        }

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
        registerTool(new com.mk65.tool.GetChatHistory());
        registerTool(new com.mk65.tool.CreateTask());
        registerTool(new com.mk65.tool.GetSystemLog());
        registerTool(new com.mk65.tool.FinishAction());
        com.mk65.tool.CreateTask.setActionPool(actionPool);
        com.mk65.tool.FinishAction.setActionPool(actionPool);
        com.mk65.tool.FinishAction.setMemoryManager(MemoryManager.getInstance());
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
        actionPool.shutdown();
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
        MK65Debug.roundStart(round, input.source(), input.rawText());

        // ★ 重置每轮结算收集器
        com.mk65.tool.FinishAction.resetRound();

        try {
            // ── 1. 分词 ──
            List<String> inputTokens = tokenizer.segment(input.actionText());
            MK65Debug.inputTokens(input.actionText(), inputTokens);

            // ── 2. 动机查询 ──
            Map<String, Map<String, Double>> dist = matrix.queryActionDistribution(inputTokens);
            Set<String> novelTokens = matrix.findNovelTokens(inputTokens);
            List<ConflictDetector.ConflictPair> rawConflicts = ConflictDetector.detectConflicts(dist);
            Map<String, Double> overallVotes = computeOverallVotes(dist);

            // ★ 共现过滤：只保留最有意义的冲突对（碎片token和从不共现的token对自然消解）
            MemoryManager mem = MemoryManager.getInstance();
            List<ConflictDetector.ConflictPair> conflicts;
            if (rawConflicts.size() > MKConfig.MOTIVATION_CONFLICT_MAX_PAIRS) {
                Map<String, Integer> tokenCounts = matrix.getTokenCounts(
                        rawConflicts.stream()
                                .flatMap(c -> java.util.stream.Stream.of(c.tokenA(), c.tokenB()))
                                .distinct()
                                .toList());
                MemoryManager.TokenOccurrenceStats occStats = mem.getTokenOccurrenceStats(
                        MKConfig.MOTIVATION_CONFLICT_COOCCUR_SCAN_LIMIT);
                conflicts = ConflictDetector.filterSignificant(
                        rawConflicts, tokenCounts, occStats, MKConfig.MOTIVATION_CONFLICT_MAX_PAIRS);
                log.info("[ActionLoop] 🎯 冲突过滤: {}→{}(top-{})",
                        rawConflicts.size(), conflicts.size(), MKConfig.MOTIVATION_CONFLICT_MAX_PAIRS);
            } else {
                conflicts = rawConflicts;
            }
            MK65Debug.motivationQuery(dist, novelTokens, conflicts, overallVotes);

            // ★ 等价token
            Map<String, Set<String>> equivalents = new HashMap<>();
            for (String token : inputTokens) {
                Set<String> eq = ConflictDetector.findEquivalents(token, dist);
                if (!eq.isEmpty()) equivalents.put(token, eq);
            }

            // ★ 自动经验检索
            java.util.List<MemoryManager.ExpMatch> autoMemories = mem.autoRecall(inputTokens, MKConfig.MEMORY_AUTO_RECALL_TOPN);
            MK65Debug.autoMemories(autoMemories);

            // ★ 对立组的历史解决方案
            Map<String, java.util.List<MemoryManager.ExpMatch>> conflictResolutions = new HashMap<>();
            if (!conflicts.isEmpty()) {
                for (ConflictDetector.ConflictPair c : conflicts) {
                    String pairKey = c.tokenA() + "|" + c.tokenB();
                    java.util.List<MemoryManager.ExpMatch> resolutions = mem.findConflictResolutions(
                            java.util.List.of(java.util.List.of(c.tokenA(), c.tokenB())));
                    if (!resolutions.isEmpty()) conflictResolutions.put(pairKey, resolutions);
                }
            }

            boolean hasHistory = !dist.isEmpty() || !autoMemories.isEmpty();

            // ★ 行动池
            actionPool.setRound(round);
            List<PrepareActionPool.ActionSummary> actionList = actionPool.getActionList();

            MotivationReport report = new MotivationReport(
                    overallVotes, dist, conflicts, novelTokens, autoMemories,
                    conflictResolutions, equivalents, actionList, hasHistory);

            // ── 3. 构建 prompt ──
            String userMessage = buildUserMessage(input.actionText(), report);
            log.info("[ActionLoop] 📏 Prompt: actionText={}chars, report={}chars, total={}chars | conflicts={}, exps={}, pool={}",
                    input.actionText().length(), userMessage.length() - input.actionText().length() - 20,
                    userMessage.length(), conflicts.size(), autoMemories.size(), actionList.size());
            report.logComponentSizes();
            ArrayNode toolsArray = buildToolsArray();

            // ── 4. 调用 LLM ──
            MK65Debug.llmCall(llm.getMessageCount(), userMessage);
            long llmStart = System.currentTimeMillis();
            LLMResult result = llm.chat(userMessage, toolsArray);

            if (result.isError()) {
                log.error("[ActionLoop] ❌ LLM 返回错误: {}", result.content());
                // ★ 错误重试：把当前输入压回准备池（降权避免死循环）
                actionPool.pushInternal("【重试】" + input.rawText(), input.priority() * 0.5);
                return;
            }
            MK65Debug.llmResponse(System.currentTimeMillis() - llmStart,
                    result.hasToolCalls() ? result.toolCalls().size() : 0,
                    result.content());

            // ── 5. 执行工具 ──
            List<String> allActionTokens = new ArrayList<>();
            List<String> toolRecords = new ArrayList<>();

            if (result.hasToolCalls()) {
                List<String> toolErrors = new ArrayList<>();

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
                            llm.appendToolResult(tc.id(), fnName, execResult);

                            log.info("[ActionLoop]   ✅ {} ({}ms): {}",
                                    fnName, toolMs,
                                    execResult.length() > 80
                                            ? execResult.substring(0, 80) + "..."
                                            : execResult);
                        } catch (Exception e) {
                            String errMsg = "[" + fnName + "] 执行异常: " + e.getMessage();
                            toolRecords.add(errMsg);
                            toolErrors.add(fnName + " 执行异常: " + e.getMessage());
                            llm.appendToolResult(tc.id(), fnName, errMsg);
                            log.error("[ActionLoop]   ❌ {} 执行异常", fnName, e);
                        }
                    } else {
                        String errMsg = "工具 \"" + fnName + "\" 未注册";
                        toolRecords.add(errMsg);
                        toolErrors.add("未知工具: " + fnName);
                        llm.appendToolResult(tc.id() != null ? tc.id() : "unknown", fnName, errMsg);
                        log.warn("[ActionLoop]   ⚠️ {}", errMsg);
                    }
                }

                // ★ 工具错误反哺：作为内源消息推入行动池，让LLM学习修正
                if (!toolErrors.isEmpty()) {
                    String errorSummary = "上一轮工具调用出现错误: " + String.join("; ", toolErrors)
                            + "。请检查工具名和参数是否正确，如果需要改用其他工具来完成原始任务。";
                    actionPool.pushInternal(errorSummary, 0.6);
                }

                allActionTokens.addAll(ProcessEncoder.encode(result.toolCalls()));
                for (String record : toolRecords) {
                    allActionTokens.addAll(ProcessEncoder.encodeTextRecord(record));
                }
            } else {
                log.info("[ActionLoop] LLM 纯文本响应: {}chars", result.content().length());
            }
            MK65Debug.actionTokens(ProcessEncoder.encode(result.toolCalls()),
                    toolRecords.stream()
                            .flatMap(r -> ProcessEncoder.encodeTextRecord(r).stream())
                            .toList());

            // ── 6. 更新共现矩阵 ──
            if (!inputTokens.isEmpty() && !allActionTokens.isEmpty()) {
                List<String> uniqueInput = inputTokens.stream().distinct().toList();
                List<String> uniqueAction = allActionTokens.stream().distinct().toList();
                MK65Debug.coMatrixUpdate(uniqueInput, uniqueAction);
                matrix.update(uniqueInput, uniqueAction);
            }

            // ── 6.5 自动录制经验 ──
            List<String> executedToolNames = result.hasToolCalls()
                    ? result.toolCalls().stream().map(ToolCall::name).toList()
                    : List.of();
            String thoughts = com.mk65.tool.FinishAction.getRoundThoughts();
            if (thoughts.isBlank()) thoughts = result.content() != null ? result.content() : "";

            // ★ 收集predecessor_ids: LLM打了1分的历史Exp
            List<Integer> predecessorIds = com.mk65.tool.FinishAction.getRoundScores().stream()
                    .filter(s -> s.score() > 0)
                    .map(com.mk65.tool.FinishAction.ExpScore::experienceId)
                    .toList();

            // ★ 收集resolved_oppositions: 本轮检测到的对立token对
            List<List<String>> resolvedOppositions = conflicts.stream()
                    .map(c -> List.of(c.tokenA(), c.tokenB()))
                    .toList();

            int expId = mem.record(round, input.actionText(), input.source(),
                    thoughts, executedToolNames, toolRecords,
                    inputTokens, allActionTokens,
                    predecessorIds, resolvedOppositions);
            MK65Debug.experienceRecorded(expId, round, executedToolNames, inputTokens, allActionTokens);

            // finish_action 结算日志 + select_next 内源加成
            var scores = com.mk65.tool.FinishAction.getRoundScores();
            var nextActions = com.mk65.tool.FinishAction.getRoundNextActions();
            int selectedNext = com.mk65.tool.FinishAction.getSelectedNext();
            if (selectedNext >= 0) {
                actionPool.applyEndogenousBoost(selectedNext);
            }
            if (!scores.isEmpty() || !nextActions.isEmpty() || selectedNext >= 0) {
                log.info("[ActionLoop] 🧠 finish_action结算: 经验打分{}条, 后续任务{}个, select_next={}",
                        scores.size(), nextActions.size(), selectedNext >= 0 ? "#" + selectedNext : "无");
            }

            // ★ 自动刺激：工具执行完后，注入内部action让LLM处理结果
            if (!toolRecords.isEmpty() && nextActions.isEmpty()) {
                // finish_action没建next_actions → 自动补一个
                List<String> infoTools = toolRecords.stream()
                        .filter(r -> r.contains("recall") || r.contains("fetch_web")
                                || r.contains("read_file") || r.contains("list_dir")
                                || r.contains("get_chat_history"))
                        .toList();
                if (!infoTools.isEmpty()) {
                    String brief = infoTools.size() == 1 ? infoTools.get(0)
                            : infoTools.size() + "个工具已返回结果";
                    actionPool.pushInternal("上一轮的" + brief + "，请查看上下文中的工具结果并决定下一步", 0.7);
                }
            }

            MK65Debug.poolState(actionPool.size(), actionPool.peek() != null
                    ? actionPool.peek().rawText() : null);

            if (!toolRecords.isEmpty()) {
                log.info("[ActionLoop] 📋 第{}轮工具执行汇总:\n  {}",
                        round, String.join("\n  ", toolRecords));
            }

            MK65Debug.roundEnd(round, inputTokens.size(), allActionTokens.size(), matrix.getCurrentRound());

        } catch (Exception e) {
            log.error("[ActionLoop] ❌ 第{}轮处理异常", round, e);
        }
    }

    // ==========================================
    // Prompt 构建
    // ==========================================

    private void loadSystemPrompt() {
        // 尝试加载FreeMarker模板，失败则用硬编码fallback
        try {
            Template tmpl = freemarker.getTemplate("SYSTEM.md.ftl");
            java.io.StringWriter sw = new java.io.StringWriter();
            Map<String, Object> sysData = new java.util.HashMap<>();
            sysData.put("selfId", com.mk65.Main.getSelfId());
            tmpl.process(sysData, sw);
            this.systemPrompt = sw.toString();
        } catch (Exception e) {
            log.warn("[ActionLoop] SYSTEM.md.ftl 加载失败，使用内置默认: {}", e.getMessage());
            this.systemPrompt = "你是运行在MK65认知架构中的AI Agent。实事求是。逻辑完备。行动规范。\\n输出JSON格式: {\\\"thoughts\\\":..., \\\"tool_calls\\\":[...]}\\n★★★每轮必须调用finish_action结算。";
        }
        llm.setSystemPrompt(systemPrompt);
        log.info("[ActionLoop] 系统指令已加载 ({}chars)", systemPrompt.length());
    }

    private String buildUserMessage(String actionText, MotivationReport report) {
        return "【当前输入】\n" + actionText + "\n\n" + report.toPromptBlock();
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
