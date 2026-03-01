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
    /** 草稿关联的媒体标识列表（如 MinIO 对象键），用逗号分隔或 JSON 字符串 */
    private String mediaIds;
    private String deviceId;
    private Long clientVersion;
    private Long updateTime;
}
