package cn.nexus.domain.social.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 内容主实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentPostEntity {
    private Long postId;
    private Long userId;
    private String contentText;
    /**
     * 帖子类型列表（业务类目/主题），由用户发布时提交。
     */
    private List<String> postTypes;
    private Integer mediaType;
    private String mediaInfo;
    private String locationInfo;
    private Integer status;
    private Integer visibility;
    private Integer versionNum;
    private Boolean edited;
    private Long createTime;
}
