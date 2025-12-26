package cn.nexus.api.social.content.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 查询内容历史请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentHistoryRequestDTO {
    private Long postId;
    private Long userId;
    private Integer limit;
}
