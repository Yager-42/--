package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IRiskLlmPort;
import cn.nexus.domain.social.model.valobj.RiskLlmResultVO;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * WSL 本地运行兜底实现：不依赖外部 LLM，统一返回人工复核。
 */
@Component
@Profile("wsl")
public class LocalRiskLlmPort implements IRiskLlmPort {

    @Override
    public RiskLlmResultVO scanText(String scenario, String actionType, String contentText, String extJson) {
        return fallback("TEXT", "WSL_LLM_DISABLED");
    }

    @Override
    public RiskLlmResultVO scanImage(String scenario, String actionType, List<String> imageUrls, String extJson) {
        return fallback("IMAGE", "WSL_LLM_DISABLED");
    }

    private RiskLlmResultVO fallback(String contentType, String reasonCode) {
        return RiskLlmResultVO.builder()
                .contentType(contentType)
                .result("REVIEW")
                .riskTags(List.of())
                .confidence(0D)
                .reasonCode(reasonCode)
                .evidence("wsl profile local fallback")
                .suggestedAction("QUARANTINE")
                .model("local-wsl-fallback")
                .build();
    }
}
