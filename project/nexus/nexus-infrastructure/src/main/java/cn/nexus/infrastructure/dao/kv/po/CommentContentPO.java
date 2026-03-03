package cn.nexus.infrastructure.dao.kv.po;

import lombok.Data;

import java.util.Date;

@Data
public class CommentContentPO {
    private Long postId;
    private String yearMonth;
    private String contentId;
    private String content;
    private Date createTime;
    private Date updateTime;
}
