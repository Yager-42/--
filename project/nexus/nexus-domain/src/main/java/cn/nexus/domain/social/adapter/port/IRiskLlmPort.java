package cn.nexus.domain.social.adapter.port;

import cn.nexus.domain.social.model.valobj.RiskLlmResultVO;

import java.util.List;

/**
 * 风控 LLM 扫描端口：对外部大模型调用做抽象，便于替换与测试。
 */
public interface IRiskLlmPort {

    RiskLlmResultVO scanText(String scenario, String actionType, String contentText, String extJson);

    RiskLlmResultVO scanImage(String scenario, String actionType, List<String> imageUrls, String extJson);
}

