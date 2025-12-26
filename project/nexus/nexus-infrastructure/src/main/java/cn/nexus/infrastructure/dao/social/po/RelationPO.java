package cn.nexus.infrastructure.dao.social.po;

import lombok.Data;

import java.util.Date;

/**
 * 关系表持久化对象，对应 user_relation。
 */
@Data
public class RelationPO {
    private Long id;
    private Long sourceId;
    private Long targetId;
    private Integer relationType;
    private Integer status;
    private Long groupId;
    private Long version;
    private Date createTime;
}
