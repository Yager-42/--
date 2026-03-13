package cn.nexus.api.social.interaction.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 点赞状态查询请求（给列表页/详情页初始化按钮状态用）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReactionStateRequestDTO {
    private Long targetId;
    private String targetType;
    private String type;
}

