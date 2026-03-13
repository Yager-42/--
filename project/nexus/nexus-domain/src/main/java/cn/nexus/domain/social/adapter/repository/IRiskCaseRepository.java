package cn.nexus.domain.social.adapter.repository;

import cn.nexus.domain.social.model.entity.RiskCaseEntity;

import java.util.List;

/**
 * 风控人审工单仓储接口。
 */
public interface IRiskCaseRepository {

    boolean insertIgnore(RiskCaseEntity entity);

    RiskCaseEntity findByCaseId(Long caseId);

    RiskCaseEntity findByDecisionId(Long decisionId);

    boolean assign(Long caseId, Long assignee, String expectedStatus);

    boolean finish(Long caseId, String result, String evidenceJson, String expectedStatus);

    List<RiskCaseEntity> list(String status, String queue, Integer limit, Integer offset);

    /**
     * 工单列表查询（可选时间过滤）。
     *
     * <p>默认实现向后兼容：若基础设施未实现时间过滤，则退化为 list(status, queue, limit, offset)。</p>
     */
    default List<RiskCaseEntity> list(String status, String queue, Long beginTimeMs, Long endTimeMs, Integer limit, Integer offset) {
        return list(status, queue, limit, offset);
    }
}
