package cn.nexus.api.social.interaction.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 获取点赞状态响应（批量）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReactionBatchStateResponseDTO {

    /**
     * 返回条目列表（与请求 targets 一一对应）。
     */
    private List<ReactionStateItemDTO> items;
}

