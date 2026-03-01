package cn.nexus.domain.social.adapter.port;

/**
 * 内容摘要生成端口（对接 AI/模型）。
 */
public interface IPostSummaryPort {

    /**
     * 生成摘要（允许返回空字符串表示“无法生成”）。
     */
    String summarize(String text, String mediaInfo);
}

