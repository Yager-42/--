package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.entity.RiskFeedbackEntity;

import java.util.List;

/**
 * 风控反馈/申诉仓储接口。
 */
public interface IRiskFeedbackRepository {

    boolean insert(RiskFeedbackEntity entity);

    RiskFeedbackEntity findById(Long feedbackId);

    boolean updateStatus(Long feedbackId, String status, String result, Long operatorId);

    List<RiskFeedbackEntity> listByUser(Long userId, Integer limit, Integer offset);
}

