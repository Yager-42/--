package cn.nexus.domain.social.adapter.port;

import cn.nexus.domain.social.model.valobj.MediaTranscodeSubmitVO;

/**
 * 媒体转码端口：负责把媒体从“原始上传”推进到“可发布”状态。
 *
 * @author {$authorName}
 * @since 2026-01-05
 */
public interface IMediaTranscodePort {
    /**
     * 提交转码任务，或判断媒体是否已就绪可直接发布。
     *
     * <p>当 {@link MediaTranscodeSubmitVO#isReady()} 为 {@code true} 时，业务可直接进入发布。</p>
     *
     * @param mediaInfo 媒体信息（通常包含媒体 URL、类型等） {@link String}
     * @return 转码提交结果 {@link MediaTranscodeSubmitVO}
     */
    MediaTranscodeSubmitVO submit(String mediaInfo);

    /**
     * 兼容旧调用：仅关心是否就绪。
     *
     * @param mediaInfo 媒体信息 {@link String}
     * @return 是否就绪 {@code boolean}
     */
    default boolean transcode(String mediaInfo) {
        return submit(mediaInfo).isReady();
    }
}
