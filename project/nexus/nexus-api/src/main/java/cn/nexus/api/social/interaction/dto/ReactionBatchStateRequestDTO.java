package cn.nexus.api.social.interaction.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 获取点赞状态请求（批量）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReactionBatchStateRequestDTO {

    /**
     * 目标列表（列表页/时间线一次查几十个，避免 N 次 DB 查询）。
     */
    private List<ReactionTargetDTO> targets;
}

