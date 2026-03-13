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
 * 风控申诉实现：以 MySQL risk_feedback 为准，处罚撤销直接更新 risk_punishment。
 */
@Service
@RequiredArgsConstructor
public class RiskAppealService implements IRiskAppealService {

    private final ISocialIdPort socialIdPort;
    private final IRiskFeedbackRepository feedbackRepository;
    private final IRiskPunishmentRepository punishmentRepository;

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

