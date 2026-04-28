package cn.nexus.infrastructure.dao.social.po;

import java.util.Date;
import lombok.Data;

@Data
public class PostCounterProjectionPO {
    private Long postId;
    private Long authorId;
    private Integer projectedPublished;
    private Long lastEventId;
    private Date createTime;
    private Date updateTime;
}
