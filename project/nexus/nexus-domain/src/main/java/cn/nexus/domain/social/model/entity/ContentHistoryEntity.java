package cn.nexus.domain.social.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 内容版本历史实体（兼容旧表）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentHistoryEntity {
    private Long historyId;
    private Long postId;
    private Integer versionNum;
    private String snapshotTitle;
    private String snapshotContent;
    private String snapshotMedia;
    private Long createTime;
}
