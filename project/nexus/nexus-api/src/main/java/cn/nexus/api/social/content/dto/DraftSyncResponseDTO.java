package cn.nexus.api.social.content.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 草稿同步结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DraftSyncResponseDTO {
    private String serverVersion;
    private Long syncTime;
}
