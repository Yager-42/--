package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IRiskPromptVersionRepository;
import cn.nexus.domain.social.model.entity.RiskPromptVersionEntity;
import cn.nexus.infrastructure.dao.social.IRiskPromptVersionDao;
import cn.nexus.infrastructure.dao.social.po.RiskPromptVersionPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 风控 Prompt 版本仓储 MyBatis 实现。
 */
@Repository
@RequiredArgsConstructor
public class RiskPromptVersionRepository implements IRiskPromptVersionRepository {

    private final IRiskPromptVersionDao promptVersionDao;

    @Override
    public boolean insert(RiskPromptVersionEntity entity) {
        if (entity == null || entity.getVersion() == null) {
            return false;
        }
        if (entity.getContentType() == null || entity.getContentType().isBlank()) {
            return false;
        }
        if (entity.getPromptText() == null || entity.getPromptText().isBlank()) {
            return false;
        }
        return promptVersionDao.insert(toPO(entity)) > 0;
    }

    @Override
    public RiskPromptVersionEntity findByVersion(Long version) {
        if (version == null) {
            return null;
        }
        return toEntity(promptVersionDao.selectByVersion(version));
    }

    @Override
    public RiskPromptVersionEntity findActive(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        return toEntity(promptVersionDao.selectActive(contentType));
    }

    @Override
    public List<RiskPromptVersionEntity> listAll(String contentType) {
        List<RiskPromptVersionPO> list = promptVersionDao.selectAll(contentType);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<RiskPromptVersionEntity> res = new ArrayList<>(list.size());
        for (RiskPromptVersionPO po : list) {
            RiskPromptVersionEntity e = toEntity(po);
            if (e != null) {
                res.add(e);
            }
        }
        return res;
    }

    @Override
    public Long maxVersion() {
        Long v = promptVersionDao.selectMaxVersion();
        return v == null ? 0L : v;
    }

    @Override
    public boolean updatePrompt(Long version, String promptText, String model, String expectedStatus) {
        if (version == null || expectedStatus == null || expectedStatus.isBlank()) {
            return false;
        }
        if (promptText == null || promptText.isBlank()) {
            return false;
        }
        return promptVersionDao.updatePrompt(version, promptText, model == null ? "" : model, expectedStatus) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean publish(Long version, Long publishBy) {
        if (version == null || publishBy == null) {
            return false;
        }
        RiskPromptVersionEntity existed = findByVersion(version);
        if (existed == null || existed.getContentType() == null || existed.getContentType().isBlank()) {
            return false;
        }
        promptVersionDao.markAllPublishedRolledBack(existed.getContentType(), "ROLLED_BACK");
        return promptVersionDao.publish(version, "PUBLISHED", publishBy, new Date()) > 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean rollback(Long toVersion, Long operatorId) {
        if (toVersion == null || operatorId == null) {
            return false;
        }
        RiskPromptVersionEntity existed = findByVersion(toVersion);
        if (existed == null || existed.getContentType() == null || existed.getContentType().isBlank()) {
            return false;
        }
        promptVersionDao.markAllPublishedRolledBack(existed.getContentType(), "ROLLED_BACK");
        return promptVersionDao.publish(toVersion, "PUBLISHED", operatorId, new Date()) > 0;
    }

    private RiskPromptVersionPO toPO(RiskPromptVersionEntity entity) {
        RiskPromptVersionPO po = new RiskPromptVersionPO();
        po.setVersion(entity.getVersion());
        po.setContentType(entity.getContentType());
        po.setStatus(entity.getStatus());
        po.setPromptText(entity.getPromptText());
        po.setModel(entity.getModel() == null ? "" : entity.getModel());
        po.setCreateBy(entity.getCreateBy());
        po.setPublishBy(entity.getPublishBy());
        po.setPublishTime(entity.getPublishTime() == null ? null : new Date(entity.getPublishTime()));
        po.setCreateTime(entity.getCreateTime() == null ? null : new Date(entity.getCreateTime()));
        po.setUpdateTime(entity.getUpdateTime() == null ? null : new Date(entity.getUpdateTime()));
        return po;
    }

    private RiskPromptVersionEntity toEntity(RiskPromptVersionPO po) {
        if (po == null) {
            return null;
        }
        Date pt = po.getPublishTime();
        Date ct = po.getCreateTime();
        Date ut = po.getUpdateTime();
        return RiskPromptVersionEntity.builder()
                .version(po.getVersion())
                .contentType(po.getContentType())
                .status(po.getStatus())
                .promptText(po.getPromptText())
                .model(po.getModel())
                .createBy(po.getCreateBy())
                .publishBy(po.getPublishBy())
                .publishTime(pt == null ? null : pt.getTime())
                .createTime(ct == null ? null : ct.getTime())
                .updateTime(ut == null ? null : ut.getTime())
                .build();
    }
}

