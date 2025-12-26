package cn.nexus.domain.social.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 关系分组实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelationGroupEntity {
    private Long groupId;
    private Long userId;
    private String groupName;
    private List<Long> memberIds;
    /**
     * 软删标记。
     */
    private Boolean deleted;
}
