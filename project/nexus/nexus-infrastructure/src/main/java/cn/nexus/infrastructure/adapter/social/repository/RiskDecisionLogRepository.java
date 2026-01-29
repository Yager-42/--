package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IRiskDecisionLogRepository;
import cn.nexus.domain.social.model.entity.RiskDecisionLogEntity;
import cn.nexus.infrastructure.dao.social.IRiskDecisionLogDao;
import cn.nexus.infrastructure.dao.social.po.RiskDecisionLogPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 风控决策日志仓储 MyBatis 实现。
 */
@Repository
@RequiredArgsConstructor
public class RiskDecisionLogRepository implements IRiskDecisionLogRepository {

    private final IRiskDecisionLogDao decisionLogDao;

    @Override
    public boolean insert(RiskDecisionLogEntity entity) {
        if (entity == null || entity.getDecisionId() == null || entity.getUserId() == null || entity.getEventId() == null) {
            return false;
        }
        return decisionLogDao.insert(toPO(entity)) > 0;
    }

    @Override
    public RiskDecisionLogEntity findByDecisionId(Long decisionId) {
        if (decisionId == null) {
            return null;
        }
        return toEntity(decisionLogDao.selectByDecisionId(decisionId));
    }

    @Override
    public RiskDecisionLogEntity findByUserEvent(Long userId, String eventId) {
        if (userId == null || eventId == null || eventId.isBlank()) {
            return null;
        }
        return toEntity(decisionLogDao.selectByUserEvent(userId, eventId));
    }

    @Override
    public boolean updateResult(Long decisionId, String result, String reasonCode, String signalsJson, String actionsJson, String extJson) {
        if (decisionId == null) {
            return false;
        }
        return decisionLogDao.updateResult(decisionId, result, reasonCode, signalsJson, actionsJson, extJson) > 0;
    }

    @Override
    public List<RiskDecisionLogEntity> listByUser(Long userId, Integer limit, Integer offset) {
        if (userId == null) {
            return List.of();
        }
        Integer l = limit == null ? 20 : Math.max(1, Math.min(limit, 200));
        Integer o = offset == null ? 0 : Math.max(0, offset);
        List<RiskDecisionLogPO> list = decisionLogDao.selectByUser(userId, l, o);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<RiskDecisionLogEntity> res = new ArrayList<>(list.size());
        for (RiskDecisionLogPO po : list) {
            RiskDecisionLogEntity e = toEntity(po);
            if (e != null) {
                res.add(e);
            }
        }
        return res;
    }

    @Override
    public List<RiskDecisionLogEntity> listByFilter(Long userId,
                                                    String actionType,
                                                    String scenario,
                                                    String result,
                                                    Long beginTimeMs,
                                                    Long endTimeMs,
                                                    Integer limit,
                                                    Integer offset) {
        Integer l = limit == null ? 20 : Math.max(1, Math.min(limit, 200));
        Integer o = offset == null ? 0 : Math.max(0, offset);
        Date bt = beginTimeMs == null ? null : new Date(beginTimeMs);
        Date et = endTimeMs == null ? null : new Date(endTimeMs);
        List<RiskDecisionLogPO> list = decisionLogDao.selectByFilter(userId, actionType, scenario, result, bt, et, l, o);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<RiskDecisionLogEntity> res = new ArrayList<>(list.size());
        for (RiskDecisionLogPO po : list) {
            RiskDecisionLogEntity e = toEntity(po);
            if (e != null) {
                res.add(e);
            }
        }
        return res;
    }

    private RiskDecisionLogPO toPO(RiskDecisionLogEntity entity) {
        RiskDecisionLogPO po = new RiskDecisionLogPO();
        po.setDecisionId(entity.getDecisionId());
        po.setEventId(entity.getEventId());
        po.setUserId(entity.getUserId());
        po.setActionType(entity.getActionType());
        po.setScenario(entity.getScenario());
        po.setResult(entity.getResult());
        po.setReasonCode(entity.getReasonCode());
        po.setRequestHash(entity.getRequestHash());
        po.setSignalsJson(entity.getSignalsJson());
        po.setActionsJson(entity.getActionsJson());
        po.setExtJson(entity.getExtJson());
        po.setTraceId(entity.getTraceId());
        po.setCreateTime(entity.getCreateTime() == null ? null : new Date(entity.getCreateTime()));
        po.setUpdateTime(entity.getUpdateTime() == null ? null : new Date(entity.getUpdateTime()));
        return po;
    }

    private RiskDecisionLogEntity toEntity(RiskDecisionLogPO po) {
        if (po == null) {
            return null;
        }
        Date ct = po.getCreateTime();
        Date ut = po.getUpdateTime();
        return RiskDecisionLogEntity.builder()
                .decisionId(po.getDecisionId())
                .eventId(po.getEventId())
                .userId(po.getUserId())
                .actionType(po.getActionType())
                .scenario(po.getScenario())
                .result(po.getResult())
                .reasonCode(po.getReasonCode())
                .requestHash(po.getRequestHash())
                .signalsJson(po.getSignalsJson())
                .actionsJson(po.getActionsJson())
                .extJson(po.getExtJson())
                .traceId(po.getTraceId())
                .createTime(ct == null ? null : ct.getTime())
                .updateTime(ut == null ? null : ut.getTime())
                .build();
    }
}
