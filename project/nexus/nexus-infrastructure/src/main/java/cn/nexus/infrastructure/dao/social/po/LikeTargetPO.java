package cn.nexus.infrastructure.dao.social.po;

import lombok.Data;

/**
 * 点赞相关批量查询目标（仅用于 MyBatis foreach 组装 where 条件）。
 */
@Data
public class LikeTargetPO {
    private String targetType;
    private Long targetId;
}

