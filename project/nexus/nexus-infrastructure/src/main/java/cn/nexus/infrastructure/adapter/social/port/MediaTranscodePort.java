package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IMediaTranscodePort;
import org.springframework.stereotype.Component;

/**
 * 媒体转码占位实现：始终返回成功。
 */
@Component
public class MediaTranscodePort implements IMediaTranscodePort {
    @Override
    public boolean transcode(String mediaInfo) {
        return true;
    }
}
