<#-- MK65 用户提示词模板 — 每轮动态注入动机报告 -->
【当前输入】
${actionText}

<#assign report = motivationReport>
<#if !report.hasHistory()>
【动机报告】（无历史数据，自由探索模式。）
<#else>
【动机报告 — 白箱统计】

<#-- 行动倾向 -->
<#if !report.overallVotes?isEmpty>
综合行动倾向:
<#list report.overallVotes?sort_by("value")?reverse?take(8) as v>
  ${v.key} — 权重: ${v.value?string("0.0")}
</#list>

各token行动分布:
<#list report.perTokenDist?keys as token>
  <#assign d = report.perTokenDist[token]>
  <#if !d?isEmpty>
  [${token}] → <#list d?sort_by("value")?reverse?take(3) as e>${e.key}(${e.value?string("0")})<#sep>, </#list>
  </#if>
</#list>

</#if>
<#-- 实践等价 -->
<#if !report.equivalentTokens?isEmpty>
🔀 实践等价（不同token指向相同行动）:
<#list report.equivalentTokens?keys as t>
  <#assign eq = report.equivalentTokens[t]>
  <#if !eq?isEmpty>
  [${t}] ≈ ${eq?join(", ")}
  </#if>
</#list>

</#if>
<#-- 行动冲突 + 历史解决方案 -->
<#if !report.conflicts?isEmpty>
⚠️ 行动冲突:
<#list report.conflicts as c>
  [${c.tokenA()}] vs [${c.tokenB()}] — 冲突度: ${c.conflictScore()?string("0.00")}
  <#assign hasRes = report.hasConflictResolution(c.tokenA(), c.tokenB())>
  <#if hasRes>
    📖 历史解决方案:
    <#list report.getConflictResolutions(c.tokenA(), c.tokenB()) as r>
       Exp#${r.id()} R${r.roundNumber()}: ${r.actionText()?substring(0, r.actionText()?length?min(80))}...
       → 工具: <#if r.toolNames()?isEmpty>(纯文本)<#else>${r.toolNames()?join(",")}</#if>
    </#list>
  <#else>
    (无历史解决方案 — 首次遇到此对立组合)
  </#if>
</#list>

</#if>
<#-- 关联历史经验 -->
<#if !report.autoMemories?isEmpty>
📖 关联历史经验:
<#list report.autoMemories as m>
  相似度 ${m.jaccard()?string("0.00")} [Exp#${m.id()} R${m.roundNumber()}]: ${m.actionText()?substring(0, m.actionText()?length?min(100))}...
  <#if !m.toolNames()?isEmpty>  → 工具: ${m.toolNames()?join(", ")}</#if>
</#list>

</#if>
<#-- 实践态度 -->
🎯 实践态度:
  行动把握度: ${report.actionConfidence()}
  场景熟悉度: ${report.sceneFamiliarity()}
  认知冲突: ${report.conflictStatus()}
  → ${report.attitudeSuggestion()}

<#-- 新异token -->
<#if !report.novelTokens?isEmpty>
🆕 新异token: ${report.novelTokens?join(", ")}
</#if>
</#if>
