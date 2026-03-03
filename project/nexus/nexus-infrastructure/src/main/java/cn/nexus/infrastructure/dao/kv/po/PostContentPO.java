package cn.nexus.infrastructure.dao.kv.po;

import lombok.Data;

import java.util.Date;

@Data
public class PostContentPO {
    private String uuid;
    private String content;
    private Date createTime;
    private Date updateTime;
}
