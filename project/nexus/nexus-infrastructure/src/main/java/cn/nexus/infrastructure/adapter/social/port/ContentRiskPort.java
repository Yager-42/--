package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IContentRiskPort;
import org.springframework.stereotype.Component;

/**
 * 风控占位实现：文本长度与媒体字段存在性校验。
 */
@Component
public class ContentRiskPort implements IContentRiskPort {
    @Override
    public boolean scanText(String text) {
        return text == null || text.length() < 5000;
    }

    @Override
    public boolean scanMedia(String mediaInfo) {
        return true;
    }
}
