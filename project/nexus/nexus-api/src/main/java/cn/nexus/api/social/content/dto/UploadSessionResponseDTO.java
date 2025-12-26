package cn.nexus.api.social.content.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上传凭证响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadSessionResponseDTO {
    private String uploadUrl;
    private String token;
    private String sessionId;
}
