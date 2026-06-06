package com.mk65.vision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mk65.config.MKConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * 视觉识别服务。
 * 使用 OpenAI 兼容的视觉 LLM API 描述图片内容。
 *
 * 调用方式：describeImages(urls) 并行请求所有图片，返回描述文本列表。
 * 默认复用 BRAIN 的 apiBase/apiKey/model，也可单独配置 vision.* 覆盖。
 */
@Slf4j
public class VisionService {

    private static volatile VisionService INSTANCE;

    private final OkHttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    private final String apiBase;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final int timeoutSec;
    private final int maxImages;
    private final boolean enabled;
    private final ExecutorService executor;

    private VisionService() {
        this.enabled = MKConfig.VISION_ENABLED;
        this.apiBase = MKConfig.VISION_API_BASE;
        this.apiKey = MKConfig.VISION_API_KEY;
        this.model = MKConfig.VISION_MODEL;
        this.maxTokens = MKConfig.VISION_MAX_TOKENS;
        this.timeoutSec = MKConfig.VISION_TIMEOUT_SEC;
        this.maxImages = MKConfig.VISION_MAX_IMAGES;

        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(timeoutSec, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();

        this.executor = Executors.newFixedThreadPool(Math.min(maxImages, 4),
                r -> {
                    Thread t = new Thread(r, "vision");
                    t.setDaemon(true);
                    return t;
                });

        log.info("[Vision] ✅ 视觉服务已初始化: enabled={}, model={}, timeout={}s, maxImages={}",
                enabled, model, timeoutSec, maxImages);
    }

    public static VisionService getInstance() {
        if (INSTANCE == null) {
            synchronized (VisionService.class) {
                if (INSTANCE == null) {
                    INSTANCE = new VisionService();
                }
            }
        }
        return INSTANCE;
    }

    /**
     * 并行描述多张图片。
     *
     * @param imageUrls 图片 URL 列表
     * @return 描述文本列表（与输入顺序一致），失败的返回 "[图片:识别失败]"
     */
    public List<String> describeImages(List<String> imageUrls) {
        if (!enabled || imageUrls == null || imageUrls.isEmpty()) {
            return List.of();
        }

        // 限制图片数量
        List<String> urls = imageUrls.size() > maxImages
                ? imageUrls.subList(0, maxImages)
                : imageUrls;

        int n = urls.size();
        List<String> results = new ArrayList<>(n);
        for (int i = 0; i < n; i++) results.add(null);

        CountDownLatch latch = new CountDownLatch(n);
        for (int i = 0; i < n; i++) {
            final int idx = i;
            final String url = urls.get(i);
            executor.submit(() -> {
                try {
                    results.set(idx, describeSingle(url));
                } catch (Exception e) {
                    log.warn("[Vision] 图片{}识别异常: {}", idx, e.getMessage());
                    results.set(idx, "[图片]"); // 降级占位
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            boolean finished = latch.await(timeoutSec * n + 5, TimeUnit.SECONDS);
            if (!finished) {
                log.warn("[Vision] 图片识别超时 ({}张, {}s)", n, timeoutSec * n + 5);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 填充未完成的结果
        for (int i = 0; i < n; i++) {
            if (results.get(i) == null) {
                results.set(i, "[图片]");
            }
        }

        return results;
    }

    /**
     * 单张图片描述。
     */
    private String describeSingle(String imageUrl) throws IOException {
        // 构建 content 数组: [text, image_url]
        ArrayNode content = mapper.createArrayNode();

        ObjectNode textPart = mapper.createObjectNode();
        textPart.put("type", "text");
        textPart.put("text", "请用一句话简洁描述这张图片的内容（不超过50字）。只描述客观可见内容，不推测、不评价。");
        content.add(textPart);

        ObjectNode imagePart = mapper.createObjectNode();
        imagePart.put("type", "image_url");
        ObjectNode imageUrlNode = mapper.createObjectNode();
        imageUrlNode.put("url", imageUrl);
        imageUrlNode.put("detail", "low");  // 低分辨率节省token
        imagePart.set("image_url", imageUrlNode);
        content.add(imagePart);

        // 构建 messages
        ArrayNode messages = mapper.createArrayNode();
        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.set("content", content);
        messages.add(userMsg);

        // 构建请求体
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.set("messages", messages);
        body.put("max_tokens", maxTokens);
        body.put("temperature", 0.3);

        String url = apiBase;
        if (!url.endsWith("/")) url += "/";
        url += "chat/completions";

        RequestBody reqBody = RequestBody.create(
                MediaType.parse("application/json"), body.toString());
        Request.Builder reqBuilder = new Request.Builder()
                .url(url)
                .post(reqBody)
                .addHeader("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            reqBuilder.addHeader("Authorization", "Bearer " + apiKey);
        }

        try (Response resp = client.newCall(reqBuilder.build()).execute()) {
            if (!resp.isSuccessful()) {
                String errBody = resp.body() != null ? resp.body().string() : "";
                log.warn("[Vision] API 返回 {}: {}",
                        resp.code(),
                        errBody.length() > 200 ? errBody.substring(0, 200) : errBody);
                return "[图片]";
            }

            String respBody = resp.body() != null ? resp.body().string() : "";
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(respBody);
            String text = root.path("choices").get(0)
                    .path("message").path("content").asText("");

            if (text.isBlank()) {
                return "[图片]";
            }

            log.debug("[Vision] 📷 识别结果: {}", text);
            return "[图片描述: " + text + "]";
        }
    }

    public boolean isEnabled() { return enabled; }
}
