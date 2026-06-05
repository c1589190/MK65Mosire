package com.mk65.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mk65.core.PrepareActionPool;
import com.mk65.motivation.MemoryManager;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 每轮必须调用的结算工具。
 *
 * 职责：
 * 1. 对自动检索到的历史经验打分（1/0/-1），驱动经验权重调整
 * 2. 为自己创建后续行动任务（替代单独的 create_task）
 * 3. 记录推理过程（存入经验库供未来检索）
 *
 * ★ 必须作为本轮最后一个工具调用。如果不调用，本轮经验不完整。
 */
@Slf4j
public class FinishAction implements MKTool {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static volatile PrepareActionPool actionPool;
    private static volatile MemoryManager memoryManager;
    private String lastRecord = null;

    // 本轮经验打分和next_actions的收集器（由ActionLoop在每轮开始前清空，工具执行完后读取）
    private static final List<ExpScore> roundScores = new ArrayList<>();
    private static final List<NextAction> roundNextActions = new ArrayList<>();
    private static String roundThoughts = "";

    public static void setActionPool(PrepareActionPool pool) { actionPool = pool; }
    public static void setMemoryManager(MemoryManager mm) { memoryManager = mm; }

    /** 每轮开始前清空收集器 */
    public static void resetRound() {
        roundScores.clear();
        roundNextActions.clear();
        roundThoughts = "";
    }

    public static List<ExpScore> getRoundScores() { return List.copyOf(roundScores); }
    public static List<NextAction> getRoundNextActions() { return List.copyOf(roundNextActions); }
    public static String getRoundThoughts() { return roundThoughts; }

    @Override
    public String getName() { return "finish_action"; }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ObjectNode fn = tool.putObject("function");
        fn.put("name", getName());
        fn.put("description", """
            ★ 每轮必须调用的结算工具。作为本轮最后一个工具调用。
            用于：对历史经验打分、为自己创建后续任务、记录推理过程。""");

        ObjectNode params = fn.putObject("parameters");
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");

        // thoughts
        ObjectNode thoughts = props.putObject("thoughts");
        thoughts.put("type", "string");
        thoughts.put("description", "本轮的推理过程。为什么这样决策、先验经验是否有用、有什么需要注意的。50-300字。");

        // experience_scoring
        ObjectNode scoring = props.putObject("experience_scoring");
        scoring.put("type", "array");
        scoring.put("description", "对【关联历史经验】中每条经验的评价。1=有帮助，0=中性，-1=没帮助/误导。只评价你有明确判断的。");
        ObjectNode items = scoring.putObject("items");
        items.put("type", "object");
        ObjectNode ip = items.putObject("properties");
        ip.putObject("experience_id").put("type", "integer").put("description", "经验ID");
        ip.putObject("score").put("type", "integer").put("description", "1有帮助 / 0中性 / -1没帮助");

        // next_actions
        ObjectNode nextActions = props.putObject("next_actions");
        nextActions.put("type", "array");
        nextActions.put("description", "为自己创建的后续任务列表。每项单独入池排队。即使当前无事可做也应有一项（如'继续监听消息'）。");
        ObjectNode naItems = nextActions.putObject("items");
        naItems.put("type", "object");
        ObjectNode nap = naItems.putObject("properties");
        nap.putObject("description").put("type", "string").put("description", "任务描述");
        nap.putObject("priority").put("type", "number").put("description", "优先级 0.0~1.0，默认0.3");

        ArrayNode required = params.putArray("required");
        required.add("thoughts");
        required.add("experience_scoring");
        required.add("next_actions");
        params.put("additionalProperties", false);
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        // 1. 提取 thoughts
        roundThoughts = arguments.path("thoughts").asText("");

        // 2. 提取经验打分
        roundScores.clear();
        JsonNode scoring = arguments.path("experience_scoring");
        if (scoring.isArray()) {
            for (JsonNode s : scoring) {
                int expId = s.path("experience_id").asInt(-1);
                int score = s.path("score").asInt(0);
                if (expId > 0) {
                    roundScores.add(new ExpScore(expId, clamp(score, -1, 1)));
                }
            }
        }

        // 3. 提取后续行动
        roundNextActions.clear();
        JsonNode next = arguments.path("next_actions");
        int created = 0;
        if (next.isArray()) {
            for (JsonNode na : next) {
                String desc = na.path("description").asText("").trim();
                if (desc.isBlank()) continue;
                double pri = na.path("priority").asDouble(0.3);
                roundNextActions.add(new NextAction(desc, pri));
                if (actionPool != null) {
                    actionPool.pushInternal(desc, pri);
                    created++;
                }
            }
        }

        // 4. 应用经验打分
        if (memoryManager != null && !roundScores.isEmpty()) {
            for (ExpScore es : roundScores) {
                memoryManager.applyScore(es.experienceId, es.score);
            }
        }

        this.lastRecord = String.format("finish_action: thoughts=%dchars, scoring=%d条, next_actions=%d条入池",
                roundThoughts.length(), roundScores.size(), created);
        log.info("[FinishAction] {}", lastRecord);

        return "SUCCESS: 本轮已结算。" +
                (created > 0 ? " 创建了 " + created + " 个后续任务。" : "") +
                (!roundScores.isEmpty() ? " 评价了 " + roundScores.size() + " 条经验。" : "");
    }

    @Override
    public String getTextRecord() {
        return lastRecord != null ? lastRecord : "finish_action: 未执行";
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    public record ExpScore(int experienceId, int score) {}
    public record NextAction(String description, double priority) {}
}
