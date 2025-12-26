package cn.nexus.domain.social.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 好友请求实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FriendRequestEntity {
    /**
     * 请求主键。
     */
    private Long requestId;
    private Long sourceId;
    private Long targetId;
    /**
     * 幂等键，source-target 组合。
     */
    private String idempotentKey;
    /**
     * 状态：1待审批，2已接受，3已拒绝。
     */
    private Integer status;
    /**
     * 乐观锁版本。
     */
    private Long version;
}
