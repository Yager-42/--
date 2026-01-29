package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.entity.RiskPromptVersionEntity;

import java.util.List;

/**
 * 风控 Prompt 版本仓储接口。
 */
public interface IRiskPromptVersionRepository {

    boolean insert(RiskPromptVersionEntity entity);

    RiskPromptVersionEntity findByVersion(Long version);

    /** 查询指定 contentType 当前生效版本（PUBLISHED） */
    RiskPromptVersionEntity findActive(String contentType);

    /** 查询版本列表（contentType 为空表示不过滤） */
    List<RiskPromptVersionEntity> listAll(String contentType);

    /** 当前最大版本号（用于生成新版本） */
    Long maxVersion();

    /** 更新 Prompt（通常只允许更新 DRAFT） */
    boolean updatePrompt(Long version, String promptText, String model, String expectedStatus);

    /** 发布指定版本（并回滚同 contentType 的旧 PUBLISHED） */
    boolean publish(Long version, Long publishBy);

    /** 回滚到指定版本（并回滚同 contentType 的旧 PUBLISHED） */
    boolean rollback(Long toVersion, Long operatorId);
}

