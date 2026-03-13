package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 媒体转码提交结果值对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaTranscodeSubmitVO {
    private boolean ready;
    private String jobId;
}

