package cn.nexus.infrastructure.dao.social.po;

import java.util.Date;
import lombok.Data;

@Data
public class Class2CounterProjectionStatePO {
    private String projectionKey;
    private String projectionType;
    private Long lastVersion;
    private Date updateTime;
}

