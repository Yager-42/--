package cn.nexus.api.social.content.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 发布内容请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublishContentRequestDTO {
    /**
     * postId（必填）。
     *
     * <p>新发帖：先调用 <code>PUT /api/v1/content/draft</code> 拿到 <code>draftId</code>，
     * 再用 <code>postId=draftId</code> 调用 publish。</p>
     */
    private Long postId;
    private Long userId;
    private String title;
    private String text;
    private String mediaInfo;
    private String location;
    private String visibility;
    /**
     * 帖子类型列表（业务类目/主题），由用户发布时提交；最多 5 个。
     *
     * <p>注意：这里的“类型”不是媒体类型（纯文/图文/视频）。</p>
     */
    private List<String> postTypes;
}
