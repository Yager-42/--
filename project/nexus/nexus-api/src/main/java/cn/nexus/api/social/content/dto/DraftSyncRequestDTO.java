package cn.nexus.api.social.content.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 草稿同步请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DraftSyncRequestDTO {
    private Long draftId;
    private String diffContent;
    private String clientVersion;
    private String deviceId;
    private java.util.List<String> mediaIds;
}
