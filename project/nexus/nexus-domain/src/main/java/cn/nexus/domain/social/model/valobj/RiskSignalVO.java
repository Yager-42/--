package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 风控信号：规则/模型/名单产生的可解释输出。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskSignalVO {
    /** RULE/MODEL/BLACKLIST/LLM */
    private String source;
    /** 信号名（建议与 ruleId/modelId 对齐） */
    private String name;
    /** 分数（0-1 或 0-100，按约定即可） */
    private Double score;
    /** 标签（例如 spam/link） */
    private List<String> tags;
    /** 细节 JSON（可选） */
    private String detailJson;
}

