package cn.nexus.api.social.content.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 内容回滚请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentRollbackRequestDTO {
    private Long postId;
    /** 请求方用户，用于ACL校验 */
    private Long userId;
    private Long targetVersionId;
}
