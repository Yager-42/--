package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.entity.RiskDecisionLogEntity;

import java.util.List;

/**
 * 风控决策日志仓储接口。
 */
public interface IRiskDecisionLogRepository {

    boolean insert(RiskDecisionLogEntity entity);

    RiskDecisionLogEntity findByDecisionId(Long decisionId);

    RiskDecisionLogEntity findByUserEvent(Long userId, String eventId);

    boolean updateResult(Long decisionId, String result, String reasonCode, String signalsJson, String actionsJson, String extJson);

    List<RiskDecisionLogEntity> listByUser(Long userId, Integer limit, Integer offset);

    /**
     * 后台查询：按 userId/actionType/scenario/result/time 过滤。
     */
    List<RiskDecisionLogEntity> listByFilter(Long userId,
                                            String actionType,
                                            String scenario,
                                            String result,
                                            Long beginTimeMs,
                                            Long endTimeMs,
                                            Integer limit,
                                            Integer offset);
}
