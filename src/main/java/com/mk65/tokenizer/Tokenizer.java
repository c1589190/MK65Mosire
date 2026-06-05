package com.mk65.tokenizer;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本分词器。
 * 对 ActionText 做中文分词 + 英文/数字直接按空格补切。
 */
@Slf4j
public class Tokenizer {

    private static volatile Tokenizer INSTANCE;
    private JiebaSegmenter segmenter;

    private Tokenizer() {
        try {
            this.segmenter = new JiebaSegmenter();
            log.info("[Tokenizer] Jieba 分词器已初始化");
        } catch (Exception e) {
            log.warn("[Tokenizer] Jieba 初始化失败，回退到字符级切分: {}", e.getMessage());
        }
    }

    public static Tokenizer getInstance() {
        if (INSTANCE == null) {
            synchronized (Tokenizer.class) {
                if (INSTANCE == null) {
                    INSTANCE = new Tokenizer();
                }
            }
        }
        return INSTANCE;
    }

    /**
     * 分词。全小写。过滤纯空白token。
     */
    public List<String> segment(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) return result;

        if (segmenter != null) {
            List<SegToken> tokens = segmenter.process(text.toLowerCase(), JiebaSegmenter.SegMode.SEARCH);
            for (SegToken t : tokens) {
                String w = t.word.trim();
                if (!w.isBlank() && w.length() > 1) {  // 过滤单字，保留有意义片段
                    result.add(w);
                }
            }
        } else {
            // fallback：按空格/标点切
            for (String part : text.toLowerCase().split("[\\s，。！？、；：\"'（）【】《》…—\\-]+")) {
                String w = part.trim();
                if (!w.isBlank()) result.add(w);
            }
        }

        return result;
    }
}
