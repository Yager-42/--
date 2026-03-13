package cn.nexus.domain.social.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 风控反馈/申诉实体：承载用户申诉、人工标注等闭环数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskFeedbackEntity {
    private Long feedbackId;
    private Long userId;
    private String type;
    private String status;
    private Long decisionId;
    private Long punishId;
    private String content;
    private String result;
    private Long operatorId;
    private Long createTime;
    private Long updateTime;
}

