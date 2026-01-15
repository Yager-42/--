package cn.nexus.infrastructure.dao.social.po;

import lombok.Data;

import java.util.Date;

/**
 * 点赞明细持久化对象，对应 likes。
 */
@Data
public class LikePO {

    private Long userId;
    private String targetType;
    private Long targetId;

    /**
     * 1=liked，0=unliked（覆盖式 upsert，便于窗口 flush 写最终态）。
     */
    private Integer status;

    private Date createTime;
    private Date updateTime;
}

