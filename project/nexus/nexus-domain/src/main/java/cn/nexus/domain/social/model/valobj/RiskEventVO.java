package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 风控事件：一次用户行为/内容输入（统一入口的数据结构）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskEventVO {
    /** 业务侧事件 ID：用于链路追踪与幂等 */
    private String eventId;
    private Long userId;
    /** 动作类型：PUBLISH_POST/COMMENT_CREATE/LOGIN/... */
    private String actionType;
    /** 场景：post.publish/comment.create/... */
    private String scenario;

    /** 文本内容（可空） */
    private String contentText;
    /** 图片 URL 列表（可空） */
    private List<String> mediaUrls;
    /** 目标对象（可空）：postId/commentId/userId... */
    private String targetId;

    /** 扩展字段 JSON（可空） */
    private String extJson;
    /** 发生时间（毫秒） */
    private Long occurTime;
}

