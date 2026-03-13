package cn.nexus.api.social.risk.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 统一风控决策请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskDecisionRequestDTO {
    /** 幂等键：业务侧生成并传入 */
    private String eventId;
    /** PUBLISH_POST/EDIT_POST/COMMENT_CREATE/DM_SEND/LOGIN/REGISTER */
    private String actionType;
    /** 场景：post.publish/comment.create/... */
    private String scenario;

    private String contentText;
    private List<String> mediaUrls;
    private String targetId;
    /** 扩展字段 JSON 字符串（可选） */
    private String ext;
}

