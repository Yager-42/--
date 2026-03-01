package cn.nexus.api.social.content.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 保存草稿请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SaveDraftRequestDTO {
    /**
     * 草稿 ID（可空）。
     *
     * <p>约定：draftId=postId。</p>
     * <ul>
     *   <li>draftId 为空：创建新草稿并返回新 ID（也是未来 postId）。</li>
     *   <li>draftId 不为空：覆盖更新该草稿（必须校验归属）。</li>
     * </ul>
     */
    private Long draftId;

    private Long userId;
    private String contentText;
    private List<String> mediaIds;
}
