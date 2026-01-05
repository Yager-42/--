package cn.nexus.infrastructure.dao.social.po;

import lombok.Data;

import java.util.Date;

/**
 * 文本修订记录（基准或补丁）。
 */
@Data
public class ContentRevisionPO {
    private Long postId;
    private Integer versionNum;
    private Integer baseVersion;
    private Integer isBase;
    private String patchHash;
    private String chunkHash;
    private String requestId;
    private Date createTime;
}
