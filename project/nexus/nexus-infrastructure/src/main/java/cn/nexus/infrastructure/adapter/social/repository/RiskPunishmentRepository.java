package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IRiskPunishmentRepository;
import cn.nexus.domain.social.model.entity.RiskPunishmentEntity;
import cn.nexus.infrastructure.dao.social.IRiskPunishmentDao;
import cn.nexus.infrastructure.dao.social.po.RiskPunishmentPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 风控处罚仓储 MyBatis 实现。
 */
@Repository
@RequiredArgsConstructor
public class RiskPunishmentRepository implements IRiskPunishmentRepository {

    private final IRiskPunishmentDao punishmentDao;

    @Override
    public boolean insert(RiskPunishmentEntity entity) {
        if (entity == null || entity.getPunishId() == null || entity.getUserId() == null) {
            return false;
        }
        return punishmentDao.insert(toPO(entity)) > 0;
    }

    @Override
    public boolean insertIgnore(RiskPunishmentEntity entity) {
        if (entity == null || entity.getPunishId() == null || entity.getUserId() == null) {
            return false;
        }
        return punishmentDao.insertIgnore(toPO(entity)) > 0;
    }

    @Override
    public RiskPunishmentEntity findByDecisionAndType(Long decisionId, String type) {
        if (decisionId == null || type == null || type.isBlank()) {
            return null;
        }
        return toEntity(punishmentDao.selectByDecisionAndType(decisionId, type));
    }

    @Override
    public List<RiskPunishmentEntity> listActiveByUser(Long userId, Long nowMs) {
        if (userId == null) {
            return List.of();
        }
        Date now = new Date(nowMs == null ? System.currentTimeMillis() : nowMs);
        List<RiskPunishmentPO> list = punishmentDao.selectActiveByUser(userId, now);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<RiskPunishmentEntity> res = new ArrayList<>(list.size());
        for (RiskPunishmentPO po : list) {
            RiskPunishmentEntity e = toEntity(po);
            if (e != null) {
                res.add(e);
            }
        }
        return res;
    }

    @Override
    public boolean revoke(Long punishId, Long operatorId) {
        if (punishId == null || operatorId == null) {
            return false;
        }
        return punishmentDao.revoke(punishId, operatorId, new Date()) > 0;
    }

    @Override
    public List<RiskPunishmentEntity> listByUser(Long userId, Integer limit, Integer offset) {
        if (userId == null) {
            return List.of();
        }
        Integer l = limit == null ? 20 : Math.max(1, Math.min(limit, 200));
        Integer o = offset == null ? 0 : Math.max(0, offset);
        List<RiskPunishmentPO> list = punishmentDao.selectByUser(userId, l, o);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<RiskPunishmentEntity> res = new ArrayList<>(list.size());
        for (RiskPunishmentPO po : list) {
            RiskPunishmentEntity e = toEntity(po);
            if (e != null) {
                res.add(e);
            }
        }
        return res;
    }

    @Override
    public List<RiskPunishmentEntity> listByFilter(Long userId,
                                                   String type,
                                                   Long beginTimeMs,
                                                   Long endTimeMs,
                                                   Integer limit,
                                                   Integer offset) {
        Integer l = limit == null ? 20 : Math.max(1, Math.min(limit, 200));
        Integer o = offset == null ? 0 : Math.max(0, offset);
        Date bt = beginTimeMs == null ? null : new Date(beginTimeMs);
        Date et = endTimeMs == null ? null : new Date(endTimeMs);
        List<RiskPunishmentPO> list = punishmentDao.selectByFilter(userId, type, bt, et, l, o);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<RiskPunishmentEntity> res = new ArrayList<>(list.size());
        for (RiskPunishmentPO po : list) {
            RiskPunishmentEntity e = toEntity(po);
            if (e != null) {
                res.add(e);
            }
        }
        return res;
    }

    private RiskPunishmentPO toPO(RiskPunishmentEntity entity) {
        RiskPunishmentPO po = new RiskPunishmentPO();
        po.setPunishId(entity.getPunishId());
        po.setUserId(entity.getUserId());
        po.setType(entity.getType());
        po.setStatus(entity.getStatus());
        po.setStartTime(entity.getStartTime() == null ? null : new Date(entity.getStartTime()));
        po.setEndTime(entity.getEndTime() == null ? null : new Date(entity.getEndTime()));
        po.setReasonCode(entity.getReasonCode());
        po.setDecisionId(entity.getDecisionId());
        po.setOperatorId(entity.getOperatorId());
        po.setCreateTime(entity.getCreateTime() == null ? null : new Date(entity.getCreateTime()));
        po.setUpdateTime(entity.getUpdateTime() == null ? null : new Date(entity.getUpdateTime()));
        return po;
    }

    private RiskPunishmentEntity toEntity(RiskPunishmentPO po) {
        if (po == null) {
            return null;
        }
        Date st = po.getStartTime();
        Date et = po.getEndTime();
        Date ct = po.getCreateTime();
        Date ut = po.getUpdateTime();
        return RiskPunishmentEntity.builder()
                .punishId(po.getPunishId())
                .userId(po.getUserId())
                .type(po.getType())
                .status(po.getStatus())
                .startTime(st == null ? null : st.getTime())
                .endTime(et == null ? null : et.getTime())
                .reasonCode(po.getReasonCode())
                .decisionId(po.getDecisionId())
                .operatorId(po.getOperatorId())
                .createTime(ct == null ? null : ct.getTime())
                .updateTime(ut == null ? null : ut.getTime())
                .build();
    }
}
