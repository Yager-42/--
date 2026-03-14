package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IPostSummaryPort;
import org.springframework.stereotype.Component;

/**
 * 摘要生成端口默认实现（占位）：当前用简单规则生成摘要，后续可替换为真实 AI/模型调用。
 *
 * @author {$authorName}
 * @since 2026-03-01
 */
@Component
public class PostSummaryPort implements IPostSummaryPort {

    private static final int MAX_LEN = 80;

    /**
     * 生成内容摘要。
     *
     * @param text 正文文本（可为空） {@link String}
     * @param mediaInfo 媒体信息（可为空） {@link String}
     * @return 摘要文本 {@link String}
     */
    @Override
    public String summarize(String text, String mediaInfo) {
        String t = text == null ? "" : text.trim();
        if (!t.isEmpty()) {
            String normalized = t.replaceAll("\\s+", " ");
            if (normalized.length() <= MAX_LEN) {
                return normalized;
            }
            return normalized.substring(0, MAX_LEN) + "...";
        }
        String media = mediaInfo == null ? "" : mediaInfo.trim();
        if (!media.isEmpty()) {
            return "一条媒体内容";
        }
        return "";
    }
}
