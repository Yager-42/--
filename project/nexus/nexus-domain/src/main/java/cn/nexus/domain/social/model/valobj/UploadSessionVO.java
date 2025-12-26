package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上传会话信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadSessionVO {
    private String uploadUrl;
    private String token;
    private String sessionId;
}
