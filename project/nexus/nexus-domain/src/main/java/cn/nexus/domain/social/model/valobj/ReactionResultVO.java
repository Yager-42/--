package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 互动结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReactionResultVO {
    /**
     * 请求标识：服务端每次生成/回传，用于链路追踪与对账。
     */
    private String requestId;
    private Long currentCount;
    /**
     * 本次请求对计数产生的增量：+1 点赞成功；-1 取消点赞成功；0 表示状态未变化（幂等）。
     *
     * <p>该字段用于旁路事件（例如评论热榜回写），不影响 HTTP 返回结构。</p>
     */
    private Integer delta;
    private boolean success;
}
