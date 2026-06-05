<#-- MK65 用户提示词模板 — 每轮动态注入 -->
【当前输入】
${actionText}

【动机报告 — 白箱统计】

<#if !hasHistory>
（无历史数据，自由探索模式。）
<#else>
<#-- 行动方向投票 -->
<#if !overallVotes?isEmpty>
综合行动倾向（当前输入token在历史上指向的行动方向汇总）:
<#list overallVotes?sort_by("value")?reverse as vote>
  ${vote.key} — 权重: ${vote.value?string("0.0")}
</#list>

各token行动分布:
<#list perTokenDist?keys as token>
  <#assign dist = perTokenDist[token]>
  <#if !dist?isEmpty>
  [${token}] → <#list dist?sort_by("value")?reverse?take(3) as e>${e.key}(${e.value?string("0")})<#sep>, </#list>
  </#if>
</#list>

</#if>
<#-- 关联历史经验 -->
<#if !autoMemories?isEmpty>
📖 关联历史经验（Jaccard场景匹配）:
<#list autoMemories as m>
  相似度 ${m.jaccard()?string("0.00")} [Exp#${m.id()} R${m.roundNumber()}]: ${m.actionText()?substring(0, m.actionText()?length?min(100))}...
  <#if !m.toolNames()?isEmpty>  → 工具: ${m.toolNames()?join(", ")}</#if>
</#list>

</#if>
<#-- 冲突 -->
<#if !conflicts?isEmpty>
⚠️ 输入内部行动冲突:
<#list conflicts as c>
  [${c.tokenA()}] vs [${c.tokenB()}] — 冲突度: ${c.conflictScore()?string("0.00")}
</#list>

</#if>
<#-- 新异token -->
<#if !novelTokens?isEmpty>
🆕 新异token: ${novelTokens?join(", ")}
</#if>
</#if>
