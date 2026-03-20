package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IMediaTranscodePort;
import cn.nexus.domain.social.model.valobj.MediaTranscodeSubmitVO;
import org.springframework.stereotype.Component;

/**
 * 媒体转码占位实现：始终返回成功。
 *
 * @author {$authorName}
 * @since 2026-01-05
 */
@Component
public class MediaTranscodePort implements IMediaTranscodePort {

    /**
     * 提交转码任务（占位实现：直接返回就绪）。
     *
     * @param mediaInfo 媒体信息（可为空） {@link String}
     * @return 转码提交结果 {@link MediaTranscodeSubmitVO}
     */
    @Override
    public MediaTranscodeSubmitVO submit(String mediaInfo) {
        return MediaTranscodeSubmitVO.builder().ready(true).jobId(null).build();
    }
}
