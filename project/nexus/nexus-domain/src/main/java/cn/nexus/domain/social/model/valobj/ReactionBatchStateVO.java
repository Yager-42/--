package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 点赞状态（批量）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReactionBatchStateVO {

    /**
     * 条目列表（与输入 targets 对齐）。
     */
    private List<ReactionStateItemVO> items;
}

