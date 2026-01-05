package cn.nexus.domain.social.adapter.port;

/**
 * 内容风控端口。
 */
public interface IContentRiskPort {
    boolean scanText(String text);
    boolean scanMedia(String mediaInfo);
}
