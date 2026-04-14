package cn.nexus.types.event.search;

import lombok.Data;

@Data
public class PostChangedCdcEvent {

    /** 推荐：binlogFile:binlogPos:rowIndex */
    private String eventId;

    private Long postId;

    private Long tsMs;

    private String source;

    private String table;
}

