package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.adapter.repository.IRiskFeedbackRepository;
import cn.nexus.domain.social.adapter.repository.IRiskPunishmentRepository;
import cn.nexus.domain.social.model.entity.RiskFeedbackEntity;
import cn.nexus.domain.social.model.valobj.OperationResultVO;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 风控申诉服务实现：受理申诉并驱动人工裁决后的处罚撤销。
 *
 * @author rr
 * @author codex
 * @since 2025-12-26
 */
@Service
@RequiredArgsConstructor
public class RiskAppealService implements IRiskAppealService {

    private final ISocialIdPort socialIdPort;
    private final IRiskFeedbackRepository feedbackRepository;
    private final IRiskPunishmentRepository punishmentRepository;

    /**
     * 提交申诉。
     *
     * @param userId 当前用户 ID。类型：{@link Long}
     * @param decisionId 关联决策 ID。类型：{@link Long}
     * @param punishId 关联处罚 ID。类型：{@link Long}
     * @param content 申诉内容。类型：{@link String}
     * @return 提交结果。类型：{@link OperationResultVO}
     */
    @Override
    public OperationResultVO submitAppeal(Long userId, Long decisionId, Long punishId, String content) {
        if (userId == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "userId 不能为空");
        }
        if (decisionId == null && punishId == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "decisionId/punishId 至少提供一个");
        }
        if (content == null || content.isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "content 不能为空");
        }
        long now = socialIdPort.now();
        long id = socialIdPort.nextId();
        // 申诉一旦受理，就先把事实落到 risk_feedback，后面人工处理都围绕这条记录推进。
        RiskFeedbackEntity entity = RiskFeedbackEntity.builder()
                .feedbackId(id)
                .userId(userId)
                .type("APPEAL")
                .status("OPEN")
                .decisionId(decisionId)
                .punishId(punishId)
                .content(content.trim())
                .result("")
                .operatorId(null)
                .createTime(now)
                .updateTime(now)
                .build();
        boolean ok = feedbackRepository.insert(entity);
        if (!ok) {
            throw new AppException(ResponseCode.UN_ERROR.getCode(), "申诉提交失败");
        }
        return OperationResultVO.builder()
                .success(true)
                .id(id)
                .status("OPEN")
                .message("OK")
                .build();
    }

    /**
     * 裁决申诉。
     *
     * @param operatorId 处理人 ID。类型：{@link Long}
     * @param appealId 申诉 ID。类型：{@link Long}
     * @param result 裁决结果。类型：{@link String}
     * @return 裁决结果。类型：{@link OperationResultVO}
     */
    @Override
    public OperationResultVO decideAppeal(Long operatorId, Long appealId, String result) {
        if (operatorId == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "operatorId 不能为空");
        }
        if (appealId == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "appealId 不能为空");
        }
        String r = normalizeAppealResult(result);
        RiskFeedbackEntity feedback = feedbackRepository.findById(appealId);
        if (feedback == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "申诉不存在");
        }
        boolean updated = feedbackRepository.updateStatus(appealId, "DONE", r, operatorId);
        if (!updated) {
            return OperationResultVO.builder().success(false).id(appealId).status("DONE").message("更新失败").build();
        }
        // 只有申诉通过并且绑定了处罚记录时，才把处罚事实同步撤销；拒绝申诉只更新申诉单状态。
        if ("ACCEPT".equalsIgnoreCase(r) && feedback.getPunishId() != null) {
            punishmentRepository.revoke(feedback.getPunishId(), operatorId);
        }
        return OperationResultVO.builder().success(true).id(appealId).status("DONE").message("OK").build();
    }

    private String normalizeAppealResult(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "result 不能为空");
        }
        String r = raw.trim().toUpperCase();
        if ("ACCEPT".equals(r) || "REJECT".equals(r)) {
            return r;
        }
        throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "result 仅支持 ACCEPT/REJECT");
    }
}

