package cn.nexus.domain.social.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 版本修订记录。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentRevisionEntity {
    private Long postId;
    private Integer versionNum;
    private Integer baseVersion;
    private Boolean isBase;
    private String patchHash;
    private String chunkHash;
    private String requestId;
    private Long createTime;
}
