package cn.nexus.domain.social.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 草稿实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentDraftEntity {
    private Long draftId;
    private Long userId;
    private String draftContent;
    private String deviceId;
    private String clientVersion;
    private Long updateTime;
}
