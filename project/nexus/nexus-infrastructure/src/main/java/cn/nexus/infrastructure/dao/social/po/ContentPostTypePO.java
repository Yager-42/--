package cn.nexus.infrastructure.dao.social.po;

import lombok.Data;

import java.util.Date;

/**
 * 内容帖子类型映射表 PO：一条记录表示 postId 关联的一个业务类型（类目/主题）。
 */
@Data
public class ContentPostTypePO {
    private Long postId;
    private String type;
    private Date createTime;
}

