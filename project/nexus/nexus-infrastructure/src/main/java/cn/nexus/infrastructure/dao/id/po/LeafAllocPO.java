package cn.nexus.infrastructure.dao.id.po;

import lombok.Data;

import java.util.Date;

@Data
public class LeafAllocPO {
    private String bizTag;
    private Long maxId;
    private Integer step;
    private String description;
    private Date updateTime;
}
