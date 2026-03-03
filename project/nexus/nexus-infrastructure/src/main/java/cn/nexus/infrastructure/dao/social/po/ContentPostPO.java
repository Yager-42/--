package cn.nexus.infrastructure.dao.social.po;

import lombok.Data;

import java.util.Date;

/**
 * 内容主表 PO。
 */
@Data
public class ContentPostPO {
    private Long postId;
    private Long userId;
    private String contentUuid;
    private String summary;
    private Integer summaryStatus;
    private Integer mediaType;
    private String mediaInfo;
    private String locationInfo;
    private Integer status;
    private Integer visibility;
    private Integer versionNum;
    private Integer isEdited;
    private Date createTime;
}
