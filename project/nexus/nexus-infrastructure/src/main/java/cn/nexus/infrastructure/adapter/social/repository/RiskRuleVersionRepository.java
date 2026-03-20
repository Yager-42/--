package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IRiskRuleVersionRepository;
import cn.nexus.domain.social.model.entity.RiskRuleVersionEntity;
import cn.nexus.infrastructure.dao.social.IRiskRuleVersionDao;
import cn.nexus.infrastructure.dao.social.po.RiskRuleVersionPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 风控规则版本仓储 MyBatis 实现。
 *
 * @author rr
 * @author codex
 * @since 2026-01-29
 */
@Repository
@RequiredArgsConstructor
public class RiskRuleVersionRepository implements IRiskRuleVersionRepository {

    private final IRiskRuleVersionDao ruleVersionDao;

    /**
     * 执行 insert 逻辑。
     *
     * @param entity entity 参数。类型：{@link RiskRuleVersionEntity}
     * @return 处理结果。类型：{@code boolean}
     */
    @Override
    public boolean insert(RiskRuleVersionEntity entity) {
        if (entity == null || entity.getVersion() == null || entity.getRulesJson() == null) {
            return false;
        }
        return ruleVersionDao.insert(toPO(entity)) > 0;
    }

    /**
     * 执行 findByVersion 逻辑。
     *
     * @param version version 参数。类型：{@link Long}
     * @return 处理结果。类型：{@link RiskRuleVersionEntity}
     */
    @Override
    public RiskRuleVersionEntity findByVersion(Long version) {
        if (version == null) {
            return null;
        }
        return toEntity(ruleVersionDao.selectByVersion(version));
    }

    /**
     * 执行 findActive 逻辑。
     *
     * @return 处理结果。类型：{@link RiskRuleVersionEntity}
     */
    @Override
    public RiskRuleVersionEntity findActive() {
        return toEntity(ruleVersionDao.selectActive());
    }

    /**
     * 执行 listAll 逻辑。
     *
     * @return 处理结果。类型：{@link List}
     */
    @Override
    public List<RiskRuleVersionEntity> listAll() {
        List<RiskRuleVersionPO> list = ruleVersionDao.selectAll();
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<RiskRuleVersionEntity> res = new ArrayList<>(list.size());
        for (RiskRuleVersionPO po : list) {
            RiskRuleVersionEntity e = toEntity(po);
            if (e != null) {
                res.add(e);
            }
        }
        return res;
    }

    /**
     * 执行 maxVersion 逻辑。
     *
     * @return 处理结果。类型：{@link Long}
     */
    @Override
    public Long maxVersion() {
        Long v = ruleVersionDao.selectMaxVersion();
        return v == null ? 0L : v;
    }

    /**
     * 执行 updateRulesJson 逻辑。
     *
     * @param version version 参数。类型：{@link Long}
     * @param rulesJson rulesJson 参数。类型：{@link String}
     * @param expectedStatus expectedStatus 参数。类型：{@link String}
     * @return 处理结果。类型：{@code boolean}
     */
    @Override
    public boolean updateRulesJson(Long version, String rulesJson, String expectedStatus) {
        if (version == null || rulesJson == null || rulesJson.isBlank() || expectedStatus == null || expectedStatus.isBlank()) {
            return false;
        }
        return ruleVersionDao.updateRulesJson(version, rulesJson, expectedStatus) > 0;
    }

    /**
     * 发布事件。
     *
     * @param version version 参数。类型：{@link Long}
     * @param publishBy publishBy 参数。类型：{@link Long}
     * @return 处理结果。类型：{@code boolean}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean publish(Long version, Long publishBy) {
        if (version == null || publishBy == null) {
            return false;
        }
        ruleVersionDao.markAllPublishedRolledBack("ROLLED_BACK");
        return ruleVersionDao.publish(version, "PUBLISHED", publishBy, new Date()) > 0;
    }

    /**
     * 执行 rollback 逻辑。
     *
     * @param toVersion toVersion 参数。类型：{@link Long}
     * @param operatorId operatorId 参数。类型：{@link Long}
     * @return 处理结果。类型：{@code boolean}
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean rollback(Long toVersion, Long operatorId) {
        if (toVersion == null || operatorId == null) {
            return false;
        }
        ruleVersionDao.markAllPublishedRolledBack("ROLLED_BACK");
        return ruleVersionDao.publish(toVersion, "PUBLISHED", operatorId, new Date()) > 0;
    }

    private RiskRuleVersionPO toPO(RiskRuleVersionEntity entity) {
        RiskRuleVersionPO po = new RiskRuleVersionPO();
        po.setVersion(entity.getVersion());
        po.setStatus(entity.getStatus());
        po.setRulesJson(entity.getRulesJson());
        po.setCreateBy(entity.getCreateBy());
        po.setPublishBy(entity.getPublishBy());
        po.setPublishTime(entity.getPublishTime() == null ? null : new Date(entity.getPublishTime()));
        po.setCreateTime(entity.getCreateTime() == null ? null : new Date(entity.getCreateTime()));
        po.setUpdateTime(entity.getUpdateTime() == null ? null : new Date(entity.getUpdateTime()));
        return po;
    }

    private RiskRuleVersionEntity toEntity(RiskRuleVersionPO po) {
        if (po == null) {
            return null;
        }
        Date pt = po.getPublishTime();
        Date ct = po.getCreateTime();
        Date ut = po.getUpdateTime();
        return RiskRuleVersionEntity.builder()
                .version(po.getVersion())
                .status(po.getStatus())
                .rulesJson(po.getRulesJson())
                .createBy(po.getCreateBy())
                .publishBy(po.getPublishBy())
                .publishTime(pt == null ? null : pt.getTime())
                .createTime(ct == null ? null : ct.getTime())
                .updateTime(ut == null ? null : ut.getTime())
                .build();
    }
}
