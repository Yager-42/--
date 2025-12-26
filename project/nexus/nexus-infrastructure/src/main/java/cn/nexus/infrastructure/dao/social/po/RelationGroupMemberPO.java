package cn.nexus.infrastructure.dao.social.po;

import lombok.Data;

import java.util.Date;

/**
 * 分组成员持久化对象。
 */
@Data
public class RelationGroupMemberPO {
    private Long id;
    private Long groupId;
    private Long memberId;
    private Date createTime;
}
