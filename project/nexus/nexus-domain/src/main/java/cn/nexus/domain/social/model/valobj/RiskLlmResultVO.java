package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * LLM 风控输出：必须结构化（JSON），用于异步链路可运维与可回放。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskLlmResultVO {
    private String contentType;      // TEXT|IMAGE
    private String result;           // PASS|REVIEW|BLOCK
    private List<String> riskTags;
    private Double confidence;
    private String reasonCode;
    private String evidence;
    private String suggestedAction;  // ALLOW|QUARANTINE|BLOCK

    /** 提示词版本号（用于对比 prompt 变更前后效果） */
    private Long promptVersion;
    /** 实际使用的模型名（可选，用于回溯） */
    private String model;
}
