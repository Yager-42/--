package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IMediaTranscodePort;
import cn.nexus.domain.social.model.valobj.MediaTranscodeSubmitVO;
import org.springframework.stereotype.Component;

/**
 * 媒体转码占位实现：始终返回成功。
 */
@Component
public class MediaTranscodePort implements IMediaTranscodePort {
    @Override
    public MediaTranscodeSubmitVO submit(String mediaInfo) {
        return MediaTranscodeSubmitVO.builder().ready(true).jobId(null).build();
    }
}
