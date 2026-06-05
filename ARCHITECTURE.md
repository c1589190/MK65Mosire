# MK65Mosire 架构规划

## 核心原则

1. **动机模型完全白箱**：不依赖外部 embedding，不训权重，不跑梯度。纯统计。
2. **LLM 负责理解和决策，动机模型负责引导注意力**：不抢 LLM 的活。
3. **行动即投票**：LLM 的 tool_calls 是对输入 token 行动方向的隐式投票，不需要额外标注。

---

## 一、整体循环

```
Adapter → ActionText → Tokenizer → [输入token序列]
                                        ↓
                              ┌── 动机模型查询 ──┐
                              │  - 每个输入token  │
                              │  - 的历史行动分布  │
                              │  - token间冲突检测 │
                              │  - 新异token标记   │
                              └──→ 动机报告 ──────┘
                                        ↓
                              LLM Prompt =
                                SystemPrompt
                                + 当前ActionText
                                + 动机报告（注入注意力方向）
                                + 工具定义
                                        ↓
                              LLM 返回 tool_calls + thoughts
                                        ↓
                              工具执行 → 外部世界
                                        ↓
                              ProcessText编码:
                                tool_calls → [action:xxx, param:yyy, ...]
                                        ↓
                              动机模型更新:
                                输入token × 行动token 共现矩阵 +1
                                        ↓
                              下一轮
```

---

## 二、动机模型（MotivationMatrix）

### 数据结构

```java
// 核心：输入token → 行动token 的共现计数
Map<String, Map<String, Integer>> coMatrix;

// 辅助：token的渐老频率（最近出现的轮次号）
Map<String, Integer> tokenLastSeenRound;
```

### 编码方法

**ActionText 编码**：Jieba/HanLP 分词 → 小写 → token 序列
- 输入："ConstantinXIV在私聊里问今天上海天气怎么样"
- 输出：["constantinxiv", "私聊", "问", "今天", "上海", "天气", "怎么样"]

**ProcessText 编码**：tool_calls → 结构化 token
- 每个 tool_call 展开为: ["action:工具名", "param:参数键1", "param:参数键2", ...]
- 参数值不编码（"上海天气"和"北京天气"是一样的行动模式）
- 示例LLM返回:
  ```json
  [{"function":{"name":"web_search","arguments":"{\"query\":\"上海天气\"}"}},
   {"function":{"name":"send_message","arguments":"{\"target\":\"qqid:3531297968\",\"message\":\"...\"}"}}]
  ```
- 编码输出：["action:web_search", "param:query", "action:send_message", "param:target", "param:message"]

### 每轮更新

```java
void update(List<String> inputTokens, List<String> actionTokens) {
    for (String it : inputTokens) {
        tokenLastSeenRound.put(it, currentRound);
        Map<String, Integer> row = coMatrix.computeIfAbsent(it, k -> new HashMap<>());
        for (String at : actionTokens) {
            row.merge(at, 1, Integer::sum);
        }
    }
}
```

### 每轮查询（生成动机报告）

1. **行动方向投票**：当前每个输入token查一行，加和得到综合投票
2. **冲突检测**：两两token的行向量余弦距离 > 阈值 → 冲突对
3. **新异标记**：tokenLastSeenRound 里找不到的，或出现次数 < 3 的

### 动机报告格式（注入 LLM prompt）

```
【动机报告】

行动方向（当前输入的token在历史上指向的行动分布）:
  天气 → action:web_search(47), param:query(47)
  上海 → action:web_search(12), param:query(10)
  今天 → action:web_search(18), action:send_message(23)
  私聊 → action:send_message(89), param:target(89)

综合行动倾向: web_search: 3.2, send_message: 2.8, query: 3.0

输入内部冲突:
  (无)

新异token（近轮首次出现或极罕见，可能指向新任务类型）:
  (无)
```

---

## 三、工具接口

沿用 BotMosire 的 `DefaultAgentToolUnit` 接口：

```java
public interface MKTools {
    String getName();                    // 工具唯一名
    ObjectNode getToolDefinition();      // OpenAI function-calling JSON Schema
    String execute(JsonNode arguments);  // 执行逻辑
    String getTextRecord();             // 给ProcessText编码用的文本描述
}
```

### 3.1 浏览器工具 — `fetch_web`

比 "web_search" 更通用的名字。不只是搜索——打开URL、读网页内容都归入信息获取类。

```
工具名: fetch_web
参数: query (string, required) — 搜索关键词或URL
      source (string, optional) — "brave"|"metaso"|"duckduckgo"，默认自动fallback
描述: 从互联网获取信息。支持搜索关键词和直接打开URL。
```

实现：复用BotMosire的 Brave → MetaSo → DuckDuckGo 三级fallback链路。

### 3.2 发送消息 — `send_message`

只对接 NapcatQQ。工具名从 `send_chat_message` 简化为 `send_message`。

```
工具名: send_message
参数: target (string, required) — "qq_group:群号" 或 "qqid:用户QQ号"
      messages (array of string, required) — 分段消息数组
描述: 向QQ群聊或私聊发送消息。控制台消息使用特殊target "console"。
```

### 3.3 文件工具 — `list_dir` / `read_file` / `write_file`

```
工具名: list_dir
参数: path (string, optional) — 目录路径，默认当前工作目录
描述: 列出指定目录下的文件和子目录。
```

```
工具名: read_file
参数: path (string, required) — 文件路径
描述: 读取文件内容。
```

```
工具名: write_file
参数: path (string, required) — 文件路径
      content (string, required) — 写入内容
      mode (string, optional) — "overwrite"|"append"，默认overwrite
描述: 写入/追加文件内容。
```

### 3.4 控制台交互

**重要设计变更**：没有独立的 `send_console_message` 工具。控制台消息复用 `send_message`，target 设为 `"console"`。

```
工具名: send_message
参数: target = "console" → 发送到控制台
      target = "qq_group:xxx" → 发送到QQ群
      target = "qqid:xxx" → 发送到QQ私聊
```

控制台输入作为特殊来源的 ActionText：
- source = "console"
- 注入 prompt 时标注 "控制台输入"

LLM 看到的：
```
【当前输入】(来源: console)
> 帮我查一下今天的天气
```

LLM 回复时调用 `send_message(target="console", messages=["今天上海晴转多云，31-33℃..."])`

---

## 四、配置结构

```properties
# ========== LLM 配置 ==========
llm.brain.apiBase=http://...
llm.brain.apiKey=sk-...
llm.brain.chatModel=deepseek-v4-flash-max
llm.brain.temperature=0.6
llm.brain.maxTokens=65535
llm.brain.stream=true

# ========== NapcatQQ 配置 ==========
napcat.wsUrl=127.0.0.1
napcat.wsPort=3001
napcat.httpUrl=http://127.0.0.1:3000
napcat.token=

# ========== 搜索配置 ==========
search.braveApiKey=
search.metasoApiKey=

# ========== 工作区 ==========
workspace.dir=workspace

# ========== 认知循环 ==========
core.tickMs=2000
core.roundTimeoutSec=180

# ========== 动机模型 ==========
motivation.conflictThreshold=0.5     # 冲突检测阈值
motivation.noveltyMinCount=3         # 新异token最低出现次数
motivation.maxInputTokens=128        # 输入token编码上限
motivation.maxActionTokens=64        # 行动token编码上限
```

---

## 五、包结构

```
com.mk65/
├── Main.java                      # 入口，初始化各组件，启动循环
├── config/
│   └── MKConfig.java              # 配置加载（properties文件 → 静态字段）
├── adapter/
│   ├── Adapter.java               # 适配器接口（start/stop/send/getHistory）
│   └── NapcatAdapter.java         # NapcatQQ WebSocket + HTTP 实现
├── llm/
│   ├── LLMAdapter.java            # OpenAI兼容API调用
│   └── LLMConfig.java             # LLM配置数据类
├── tokenizer/
│   └── Tokenizer.java             # 分词（Jieba/HanLP封装）
├── motivation/
│   ├── MotivationMatrix.java      # 共现矩阵（核心）
│   ├── ConflictDetector.java      # token间冲突计算
│   └── MotivationReport.java      # 动机报告生成 + 数据类
├── tool/
│   ├── MKTool.java                # 工具接口（getName/getDef/execute/getRecord）
│   ├── FetchWeb.java              # 浏览器搜索/打开URL
│   ├── SendMessage.java           # 发消息（NapcatQQ + Console统一）
│   ├── ListDir.java               # ls目录
│   ├── ReadFile.java              # 读文件
│   └── WriteFile.java             # 写文件
├── core/
│   ├── ActionLoop.java            # 主编排器（tick → 编码 → 动机 → LLM → 执行 → 更新）
│   ├── ActionTextBuilder.java     # 从Adapter输入拼装ActionText
│   └── ProcessEncoder.java        # 将LLM的tool_calls编码为行动token序列
└── workspace/
    └── WorkspaceManager.java      # 文件沙盒管理（cd/ls/read/write）
```

---

## 六、首轮冷启动

第一轮没有任何历史数据，共现矩阵为空：

1. ActionText → tokenize → 所有token查矩阵返回空
2. 动机报告：「无历史数据，自由探索」
3. LLM 基于 SystemPrompt + ActionText 自行决策
4. 执行工具 → ProcessText编码 → **第一批数据写入共现矩阵**
5. 第二轮开始，动机模型有数据可查

---

## 七、与BotMosire的关键区别

| | BotMosire | MK65Mosire |
|---|---|---|
| 感觉系统 | FeelingUnit标签匹配 + 外部embedding | 共现矩阵，纯统计，白箱 |
| 经验系统 | ExperiencesDB + helpful_degree打分 | 不需要——共现矩阵本身就是经验的统计沉淀 |
| 注意力度量 | 语义BFS + 余弦相似度 | 输入token → 行动token 的共现频率 |
| 冲突检测 | 感觉维度embedding的余弦距离 | 输入token间行动分布的余弦距离 |
| 新异检测 | CognitiveFamiliarity数值 | token首次出现/低频率 |
| Embedding | 外部模型 (text-embedding/Qwen-Embedding) | 不需要 |
| 动机注入 | 感觉谐振分析/违和检测/模板匹配 | 单一动机报告：投票+冲突+新异 |
| Console消息 | 独立工具 send_console_message | send_message(target="console") |
| 浏览器工具名 | web_search + read_webpage | fetch_web（统一信息获取） |
