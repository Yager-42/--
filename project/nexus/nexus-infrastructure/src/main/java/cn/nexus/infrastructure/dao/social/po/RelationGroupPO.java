package cn.nexus.infrastructure.dao.social.po;

import lombok.Data;

import java.util.Date;

/**
 * 分组持久化对象，对应 user_relation_group。
 */
@Data
public class RelationGroupPO {
    private Long groupId;
    private Long userId;
    private String groupName;
    private Boolean deleted;
    private Date createTime;
}
