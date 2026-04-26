package cn.nexus.domain.social.model.valobj;

import java.util.Date;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class Class2CounterProjectionStateVO {
    String projectionKey;
    String projectionType;
    Long lastVersion;
    Date updateTime;
}

