package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.entity.RiskPunishmentEntity;

import java.util.List;

/**
 * 风控处罚仓储接口。
 */
public interface IRiskPunishmentRepository {

    boolean insert(RiskPunishmentEntity entity);

    boolean insertIgnore(RiskPunishmentEntity entity);

    RiskPunishmentEntity findByDecisionAndType(Long decisionId, String type);

    List<RiskPunishmentEntity> listActiveByUser(Long userId, Long nowMs);

    boolean revoke(Long punishId, Long operatorId);

    List<RiskPunishmentEntity> listByUser(Long userId, Integer limit, Integer offset);

    /**
     * 后台查询：按 userId/type/time 过滤（time 默认按 create_time）。
     */
    List<RiskPunishmentEntity> listByFilter(Long userId,
                                           String type,
                                           Long beginTimeMs,
                                           Long endTimeMs,
                                           Integer limit,
                                           Integer offset);
}
