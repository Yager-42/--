package cn.nexus.domain.social.adapter.port;

import cn.nexus.domain.social.model.valobj.MediaTranscodeSubmitVO;

/**
 * 媒体转码端口。
 */
public interface IMediaTranscodePort {
    /**
     * 提交转码或校验是否可直接发布。
     * <p>
     * - ready=true：媒体已就绪，可直接进入“提交可见版本”。\n
     * - ready=false：返回 jobId（可选），后续由异步回调/轮询推进。\n
     */
    MediaTranscodeSubmitVO submit(String mediaInfo);

    /**
     * 兼容旧调用：仅关心是否就绪。
     */
    default boolean transcode(String mediaInfo) {
        return submit(mediaInfo).isReady();
    }
}
