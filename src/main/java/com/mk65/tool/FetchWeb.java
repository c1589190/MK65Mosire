package com.mk65.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mk65.config.MKConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 通用互联网信息获取工具。
 * 支持搜索关键词和直接打开URL。
 * 搜索引擎fallback链: Brave → MetaSo → DuckDuckGo
 */
@Slf4j
public class FetchWeb implements MKTool {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();

    private String lastRecord = null;

    @Override
    public String getName() { return "fetch_web"; }

    @Override
    public ObjectNode getToolDefinition() {
        ObjectNode tool = mapper.createObjectNode();
        tool.put("type", "function");

        ObjectNode fn = tool.putObject("function");
        fn.put("name", getName());
        fn.put("description", "从互联网获取信息。支持搜索关键词，也可以直接提取指定URL的网页内容。");

        ObjectNode params = fn.putObject("parameters");
        params.put("type", "object");

        ObjectNode props = params.putObject("properties");

        ObjectNode query = props.putObject("query");
        query.put("type", "string");
        query.put("description", "搜索关键词或完整URL。如果是URL（以http://或https://开头），则直接提取该网页内容；否则执行搜索。");

        ObjectNode count = props.putObject("count");
        count.put("type", "integer");
        count.put("description", "返回结果数量（仅搜索模式有效），默认5，最大8。");

        ObjectNode source = props.putObject("source");
        source.put("type", "string");
        source.put("description", "指定搜索来源: brave, metaso, duckduckgo。默认自动选择第一个可用的。");

        params.putArray("required").add("query");
        params.put("additionalProperties", false);
        return tool;
    }

    @Override
    public String execute(JsonNode arguments) {
        String query = arguments.path("query").asText("").trim();
        if (query.isBlank()) return "ERROR: query 参数不能为空。";

        int count = Math.min(Math.max(arguments.path("count").asInt(5), 1), 8);
        String source = arguments.path("source").asText("").trim();
        this.lastRecord = "fetch_web: " + (query.length() > 60 ? query.substring(0, 60) + "..." : query);

        // 如果是URL，直接提取网页内容
        if (query.startsWith("http://") || query.startsWith("https://")) {
            return fetchUrlContent(query);
        }

        // 搜索模式
        if ("brave".equalsIgnoreCase(source)) {
            String r = searchWithBrave(query, count);
            if (!r.startsWith("ERROR")) return r;
        } else if ("metaso".equalsIgnoreCase(source)) {
            String r = searchWithMetaso(query, count);
            if (!r.startsWith("ERROR")) return r;
        } else if ("duckduckgo".equalsIgnoreCase(source)) {
            return searchWithDuckDuckGo(query, count);
        }

        // 自动 fallback
        String braveKey = MKConfig.SEARCH_BRAVE_API_KEY;
        if (braveKey != null && !braveKey.isBlank()) {
            String r = searchWithBrave(query, count);
            if (!r.startsWith("ERROR")) return r;
        }

        String metasoKey = MKConfig.SEARCH_METASO_API_KEY;
        if (metasoKey != null && !metasoKey.isBlank()) {
            String r = searchWithMetaso(query, count);
            if (!r.startsWith("ERROR")) return r;
        }

        return searchWithDuckDuckGo(query, count);
    }

    @Override
    public String getTextRecord() {
        return lastRecord != null ? lastRecord : "fetch_web: 未执行";
    }

    // ── URL 内容提取 ──

    private String fetchUrlContent(String url) {
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return "ERROR: HTTP " + response.code();
                }
                String html = response.body().string();
                Document doc = Jsoup.parse(html);
                String text = doc.body().text();
                if (text.length() > 4000) {
                    text = text.substring(0, 4000) + "...";
                }
                this.lastRecord = "fetch_web: 提取URL内容 " + url;
                return "【网页内容】" + url + "\n\n" + text;
            }
        } catch (Exception e) {
            log.error("[FetchWeb] URL提取失败: {}", url, e);
            return "ERROR: URL提取失败 - " + e.getMessage();
        }
    }

    // ── Brave Search ──

    private String searchWithBrave(String query, int count) {
        try {
            String url = "https://api.search.brave.com/res/v1/web/search?q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&count=" + count;
            Request request = new Request.Builder()
                    .url(url).header("Accept", "application/json")
                    .header("Accept-Encoding", "gzip")
                    .header("X-Subscription-Token", MKConfig.SEARCH_BRAVE_API_KEY)
                    .build();
            try (Response r = client.newCall(request).execute()) {
                if (!r.isSuccessful() || r.body() == null) return "ERROR: Brave HTTP " + r.code();
                JsonNode root = mapper.readTree(r.body().string());
                JsonNode results = root.path("web").path("results");
                if (!results.isArray() || results.isEmpty()) return "SYSTEM_FEEDBACK: 未找到相关结果。";
                return formatResults(query, parseSearchResults(results, count), "Brave");
            }
        } catch (Exception e) {
            log.error("[FetchWeb] Brave异常", e);
            return "ERROR: " + e.getMessage();
        }
    }

    // ── MetaSo Search ──

    private String searchWithMetaso(String query, int count) {
        try {
            String url = "https://metaso.cn/api/v1/search";
            ObjectNode body = mapper.createObjectNode();
            body.put("q", query);
            body.put("scope", "webpage");
            body.put("includeSummary", false);
            body.put("size", String.valueOf(count));

            RequestBody reqBody = RequestBody.create(
                    MediaType.parse("application/json"), body.toString());
            Request request = new Request.Builder()
                    .url(url).method("POST", reqBody)
                    .addHeader("Authorization", "Bearer " + MKConfig.SEARCH_METASO_API_KEY)
                    .addHeader("Accept", "application/json")
                    .addHeader("Content-Type", "application/json")
                    .build();
            try (Response r = client.newCall(request).execute()) {
                if (!r.isSuccessful() || r.body() == null) return "ERROR: MetaSo HTTP " + r.code();
                JsonNode root = mapper.readTree(r.body().string());
                JsonNode webpages = root.path("webpages");
                if (!webpages.isArray() || webpages.isEmpty()) return "SYSTEM_FEEDBACK: 未找到相关结果。";

                List<SearchResult> list = new ArrayList<>();
                for (JsonNode page : webpages) {
                    if (list.size() >= count) break;
                    SearchResult sr = new SearchResult();
                    sr.title = page.path("title").asText("");
                    sr.url = page.path("link").asText("");
                    sr.snippet = page.path("snippet").asText("");
                    if (!sr.url.isBlank()) list.add(sr);
                }
                return formatResults(query, list, "MetaSo");
            }
        } catch (Exception e) {
            log.error("[FetchWeb] MetaSo异常", e);
            return "ERROR: " + e.getMessage();
        }
    }

    // ── DuckDuckGo ──

    private String searchWithDuckDuckGo(String query, int count) {
        try {
            String url = "https://html.duckduckgo.com/html/?q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8);
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .build();

            String html;
            try (Response r = client.newCall(request).execute()) {
                if (!r.isSuccessful() || r.body() == null) return "ERROR: DuckDuckGo HTTP " + r.code();
                html = r.body().string();
            }
            Document doc = Jsoup.parse(html);
            Elements resultEls = doc.select("div.result__body");

            List<SearchResult> list = new ArrayList<>();
            for (Element el : resultEls) {
                if (list.size() >= count) break;
                Element titleEl = el.selectFirst("a.result__a");
                Element snippetEl = el.selectFirst("a.result__snippet");
                if (titleEl == null) continue;

                SearchResult sr = new SearchResult();
                sr.title = titleEl.text();
                sr.url = extractDdgUrl(titleEl.attr("href"));
                sr.snippet = snippetEl != null ? snippetEl.text() : "";
                if (sr.url != null && !sr.url.isBlank()) list.add(sr);
            }
            if (list.isEmpty()) return "SYSTEM_FEEDBACK: 未找到相关结果。";
            return formatResults(query, list, "DuckDuckGo");
        } catch (Exception e) {
            log.error("[FetchWeb] DuckDuckGo异常", e);
            return "ERROR: " + e.getMessage();
        }
    }

    private List<SearchResult> parseSearchResults(JsonNode results, int count) {
        List<SearchResult> list = new ArrayList<>();
        for (JsonNode r : results) {
            if (list.size() >= count) break;
            SearchResult sr = new SearchResult();
            sr.title = r.path("title").asText("");
            sr.url = r.path("url").asText("");
            sr.snippet = r.path("description").asText("");
            if (!sr.url.isBlank()) list.add(sr);
        }
        return list;
    }

    private static String extractDdgUrl(String href) {
        if (href == null || href.isBlank()) return null;
        if (href.startsWith("http")) return href;
        int idx = href.indexOf("uddg=");
        if (idx < 0) return null;
        String encoded = href.substring(idx + 5);
        int amp = encoded.indexOf('&');
        if (amp > 0) encoded = encoded.substring(0, amp);
        try { return URLDecoder.decode(encoded, StandardCharsets.UTF_8); } catch (Exception e) { return null; }
    }

    private String formatResults(String query, List<SearchResult> results, String source) {
        this.lastRecord = "fetch_web: 搜索 [" + query + "] (" + source + ", " + results.size() + "条结果)";
        StringBuilder sb = new StringBuilder();
        sb.append("【搜索结果】关键词: \"").append(query).append("\" 来源: ").append(source).append("\n\n");
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            sb.append(i + 1).append(". ").append(r.title).append("\n");
            sb.append("   URL: ").append(r.url).append("\n");
            if (!r.snippet.isBlank()) sb.append("   ").append(r.snippet).append("\n");
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    private static class SearchResult {
        String title = "", url = "", snippet = "";
    }
}
