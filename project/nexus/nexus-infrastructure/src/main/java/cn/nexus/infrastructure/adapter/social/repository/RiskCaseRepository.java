package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IRiskCaseRepository;
import cn.nexus.domain.social.model.entity.RiskCaseEntity;
import cn.nexus.infrastructure.dao.social.IRiskCaseDao;
import cn.nexus.infrastructure.dao.social.po.RiskCasePO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 风控工单仓储 MyBatis 实现。
 */
@Repository
@RequiredArgsConstructor
public class RiskCaseRepository implements IRiskCaseRepository {

    private final IRiskCaseDao caseDao;

    @Override
    public boolean insertIgnore(RiskCaseEntity entity) {
        if (entity == null || entity.getCaseId() == null || entity.getDecisionId() == null) {
            return false;
        }
        return caseDao.insertIgnore(toPO(entity)) > 0;
    }

    @Override
    public RiskCaseEntity findByCaseId(Long caseId) {
        if (caseId == null) {
            return null;
        }
        return toEntity(caseDao.selectById(caseId));
    }

    @Override
    public RiskCaseEntity findByDecisionId(Long decisionId) {
        if (decisionId == null) {
            return null;
        }
        return toEntity(caseDao.selectByDecisionId(decisionId));
    }

    @Override
    public boolean assign(Long caseId, Long assignee, String expectedStatus) {
        if (caseId == null || assignee == null || expectedStatus == null || expectedStatus.isBlank()) {
            return false;
        }
        return caseDao.updateAssign(caseId, assignee, expectedStatus) > 0;
    }

    @Override
    public boolean finish(Long caseId, String result, String evidenceJson, String expectedStatus) {
        if (caseId == null || expectedStatus == null || expectedStatus.isBlank()) {
            return false;
        }
        return caseDao.updateFinish(caseId, result, evidenceJson, expectedStatus) > 0;
    }

    @Override
    public List<RiskCaseEntity> list(String status, String queue, Integer limit, Integer offset) {
        Integer l = limit == null ? 20 : Math.max(1, Math.min(limit, 200));
        Integer o = offset == null ? 0 : Math.max(0, offset);
        List<RiskCasePO> list = caseDao.selectList(status, queue, null, null, l, o);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<RiskCaseEntity> res = new ArrayList<>(list.size());
        for (RiskCasePO po : list) {
            RiskCaseEntity e = toEntity(po);
            if (e != null) {
                res.add(e);
            }
        }
        return res;
    }

    @Override
    public List<RiskCaseEntity> list(String status, String queue, Long beginTimeMs, Long endTimeMs, Integer limit, Integer offset) {
        Integer l = limit == null ? 20 : Math.max(1, Math.min(limit, 200));
        Integer o = offset == null ? 0 : Math.max(0, offset);
        Date bt = beginTimeMs == null ? null : new Date(beginTimeMs);
        Date et = endTimeMs == null ? null : new Date(endTimeMs);
        List<RiskCasePO> list = caseDao.selectList(status, queue, bt, et, l, o);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<RiskCaseEntity> res = new ArrayList<>(list.size());
        for (RiskCasePO po : list) {
            RiskCaseEntity e = toEntity(po);
            if (e != null) {
                res.add(e);
            }
        }
        return res;
    }

    private RiskCasePO toPO(RiskCaseEntity entity) {
        RiskCasePO po = new RiskCasePO();
        po.setCaseId(entity.getCaseId());
        po.setDecisionId(entity.getDecisionId());
        po.setStatus(entity.getStatus());
        po.setQueue(entity.getQueue());
        po.setAssignee(entity.getAssignee());
        po.setResult(entity.getResult());
        po.setEvidenceJson(entity.getEvidenceJson());
        po.setCreateTime(entity.getCreateTime() == null ? null : new Date(entity.getCreateTime()));
        po.setUpdateTime(entity.getUpdateTime() == null ? null : new Date(entity.getUpdateTime()));
        return po;
    }

    private RiskCaseEntity toEntity(RiskCasePO po) {
        if (po == null) {
            return null;
        }
        Date ct = po.getCreateTime();
        Date ut = po.getUpdateTime();
        return RiskCaseEntity.builder()
                .caseId(po.getCaseId())
                .decisionId(po.getDecisionId())
                .status(po.getStatus())
                .queue(po.getQueue())
                .assignee(po.getAssignee())
                .result(po.getResult())
                .evidenceJson(po.getEvidenceJson())
                .createTime(ct == null ? null : ct.getTime())
                .updateTime(ut == null ? null : ut.getTime())
                .build();
    }
}
