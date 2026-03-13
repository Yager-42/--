package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.entity.RiskRuleVersionEntity;

import java.util.List;

/**
 * 风控规则版本仓储接口。
 */
public interface IRiskRuleVersionRepository {

    boolean insert(RiskRuleVersionEntity entity);

    RiskRuleVersionEntity findByVersion(Long version);

    RiskRuleVersionEntity findActive();

    List<RiskRuleVersionEntity> listAll();

    /** 当前最大版本号（用于生成新版本） */
    Long maxVersion();

    /** 更新规则配置（通常只允许更新 DRAFT） */
    boolean updateRulesJson(Long version, String rulesJson, String expectedStatus);

    boolean publish(Long version, Long publishBy);

    boolean rollback(Long toVersion, Long operatorId);
}
