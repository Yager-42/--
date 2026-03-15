package cn.nexus.infrastructure.adapter.social.repository;

import cn.nexus.domain.social.adapter.repository.IRiskFeedbackRepository;
import cn.nexus.domain.social.model.entity.RiskFeedbackEntity;
import cn.nexus.infrastructure.dao.social.IRiskFeedbackDao;
import cn.nexus.infrastructure.dao.social.po.RiskFeedbackPO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 风控反馈/申诉仓储 MyBatis 实现。
 *
 * @author rr
 * @author codex
 * @since 2026-01-29
 */
@Repository
@RequiredArgsConstructor
public class RiskFeedbackRepository implements IRiskFeedbackRepository {

    private final IRiskFeedbackDao feedbackDao;

    /**
     * 执行 insert 逻辑。
     *
     * @param entity entity 参数。类型：{@link RiskFeedbackEntity}
     * @return 处理结果。类型：{@code boolean}
     */
    @Override
    public boolean insert(RiskFeedbackEntity entity) {
        if (entity == null || entity.getFeedbackId() == null || entity.getUserId() == null) {
            return false;
        }
        return feedbackDao.insert(toPO(entity)) > 0;
    }

    /**
     * 执行 findById 逻辑。
     *
     * @param feedbackId feedbackId 参数。类型：{@link Long}
     * @return 处理结果。类型：{@link RiskFeedbackEntity}
     */
    @Override
    public RiskFeedbackEntity findById(Long feedbackId) {
        if (feedbackId == null) {
            return null;
        }
        return toEntity(feedbackDao.selectById(feedbackId));
    }

    /**
     * 执行 updateStatus 逻辑。
     *
     * @param feedbackId feedbackId 参数。类型：{@link Long}
     * @param status status 参数。类型：{@link String}
     * @param result result 参数。类型：{@link String}
     * @param operatorId operatorId 参数。类型：{@link Long}
     * @return 处理结果。类型：{@code boolean}
     */
    @Override
    public boolean updateStatus(Long feedbackId, String status, String result, Long operatorId) {
        if (feedbackId == null || status == null || status.isBlank()) {
            return false;
        }
        return feedbackDao.updateStatus(feedbackId, status, result, operatorId) > 0;
    }

    /**
     * 执行 listByUser 逻辑。
     *
     * @param userId 当前用户 ID。类型：{@link Long}
     * @param limit 分页大小。类型：{@link Integer}
     * @param offset offset 参数。类型：{@link Integer}
     * @return 处理结果。类型：{@link List}
     */
    @Override
    public List<RiskFeedbackEntity> listByUser(Long userId, Integer limit, Integer offset) {
        if (userId == null) {
            return List.of();
        }
        Integer l = limit == null ? 20 : Math.max(1, Math.min(limit, 200));
        Integer o = offset == null ? 0 : Math.max(0, offset);
        List<RiskFeedbackPO> list = feedbackDao.selectByUser(userId, l, o);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<RiskFeedbackEntity> res = new ArrayList<>(list.size());
        for (RiskFeedbackPO po : list) {
            RiskFeedbackEntity e = toEntity(po);
            if (e != null) {
                res.add(e);
            }
        }
        return res;
    }

    private RiskFeedbackPO toPO(RiskFeedbackEntity entity) {
        RiskFeedbackPO po = new RiskFeedbackPO();
        po.setFeedbackId(entity.getFeedbackId());
        po.setUserId(entity.getUserId());
        po.setType(entity.getType());
        po.setStatus(entity.getStatus());
        po.setDecisionId(entity.getDecisionId());
        po.setPunishId(entity.getPunishId());
        po.setContent(entity.getContent());
        po.setResult(entity.getResult());
        po.setOperatorId(entity.getOperatorId());
        po.setCreateTime(entity.getCreateTime() == null ? null : new Date(entity.getCreateTime()));
        po.setUpdateTime(entity.getUpdateTime() == null ? null : new Date(entity.getUpdateTime()));
        return po;
    }

    private RiskFeedbackEntity toEntity(RiskFeedbackPO po) {
        if (po == null) {
            return null;
        }
        Date ct = po.getCreateTime();
        Date ut = po.getUpdateTime();
        return RiskFeedbackEntity.builder()
                .feedbackId(po.getFeedbackId())
                .userId(po.getUserId())
                .type(po.getType())
                .status(po.getStatus())
                .decisionId(po.getDecisionId())
                .punishId(po.getPunishId())
                .content(po.getContent())
                .result(po.getResult())
                .operatorId(po.getOperatorId())
                .createTime(ct == null ? null : ct.getTime())
                .updateTime(ut == null ? null : ut.getTime())
                .build();
    }
}

