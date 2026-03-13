package cn.nexus.domain.social.model.entity;

import java.util.Date;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户关系实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelationEntity {
    /**
     * 关系记录主键。
     */
    private Long id;
    /**
     * 源用户。
     */
    private Long sourceId;
    /**
     * 目标用户。
     */
    private Long targetId;
    /**
     * 关系类型：1关注、3屏蔽。
     */
    private Integer relationType;
    /**
     * 状态：1正常/通过。
     */
    private Integer status;
    /**
     * 所属分组。
     */
    private Long groupId;
    /**
     * 乐观锁版本。
     */
    private Long version;
    /**
     * 创建时间。
     */
    private Date createTime;
}
