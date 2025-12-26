package cn.nexus.api.social.content.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上传凭证请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadSessionRequestDTO {
    private String fileType;
    private Long fileSize;
    private String crc32;
}
