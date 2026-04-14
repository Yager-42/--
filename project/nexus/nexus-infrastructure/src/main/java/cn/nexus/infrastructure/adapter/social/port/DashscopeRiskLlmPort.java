package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IRiskLlmPort;
import cn.nexus.domain.social.adapter.repository.IRiskPromptVersionRepository;
import cn.nexus.domain.social.model.entity.RiskPromptVersionEntity;
import cn.nexus.domain.social.model.valobj.RiskLlmResultVO;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

import java.net.URL;
import java.util.List;

/**
 * Spring AI Alibaba DashScope LLM 适配：用于异步内容风控扫描。
 *
 * @author rr
 * @author codex
 * @since 2026-01-29
 */
@Slf4j
@Component
@Profile("!wsl")
@RequiredArgsConstructor
public class DashscopeRiskLlmPort implements IRiskLlmPort {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final IRiskPromptVersionRepository promptVersionRepository;

    @Value("${risk.llm.text-model:}")
    private String textModel;

    @Value("${risk.llm.image-model:}")
    private String imageModel;

    /**
     * 执行 scanText 逻辑。
     *
     * @param scenario scenario 参数。类型：{@link String}
     * @param actionType actionType 参数。类型：{@link String}
     * @param contentText contentText 参数。类型：{@link String}
     * @param extJson extJson 参数。类型：{@link String}
     * @return 处理结果。类型：{@link RiskLlmResultVO}
     */
    @Override
    public RiskLlmResultVO scanText(String scenario, String actionType, String contentText, String extJson) {
        PromptSnapshot snap = loadActivePrompt("TEXT");
        String modelToUse = chooseModel(textModel, snap);
        String sys = systemPrompt("TEXT", scenario, actionType, snap);
        String user = contentText == null ? "" : contentText;
        Prompt prompt = new Prompt(List.of(new SystemMessage(sys), new UserMessage(user)), options(modelToUse));
        return callAndParse(prompt, "TEXT", snap, modelToUse);
    }

    /**
     * 执行 scanImage 逻辑。
     *
     * @param scenario scenario 参数。类型：{@link String}
     * @param actionType actionType 参数。类型：{@link String}
     * @param imageUrls imageUrls 参数。类型：{@link List}
     * @param extJson extJson 参数。类型：{@link String}
     * @return 处理结果。类型：{@link RiskLlmResultVO}
     */
    @Override
    public RiskLlmResultVO scanImage(String scenario, String actionType, List<String> imageUrls, String extJson) {
        PromptSnapshot snap = loadActivePrompt("IMAGE");
        String modelToUse = chooseModel(imageModel, snap);
        String sys = systemPrompt("IMAGE", scenario, actionType, snap);
        String url = firstUrl(imageUrls);
        if (url == null) {
            return fallback("IMAGE", "IMAGE_URL_EMPTY", trim(null), snap, modelToUse);
        }
        try {
            UserMessage userMessage = UserMessage.builder()
                    .text("Please output the moderation result as JSON only.")
                    .media(Media.builder().mimeType(MimeTypeUtils.IMAGE_JPEG).data(new URL(url)).build())
                    .build();
            Prompt prompt = new Prompt(List.of(new SystemMessage(sys), userMessage), options(modelToUse));
            return callAndParse(prompt, "IMAGE", snap, modelToUse);
        } catch (Exception e) {
            log.warn("dashscope image scan failed, url={}", url, e);
            return fallback("IMAGE", "LLM_IMAGE_ERROR", trim(e.getMessage()), snap, modelToUse);
        }
    }

    private RiskLlmResultVO callAndParse(Prompt prompt, String fallbackType, PromptSnapshot snap, String modelUsed) {
        try {
            ChatResponse response = chatModel.call(prompt);
            String raw = response == null || response.getResult() == null || response.getResult().getOutput() == null
                    ? null
                    : response.getResult().getOutput().getText();
            RiskLlmResultVO parsed = parseJson(raw);
            if (parsed != null) {
                if (parsed.getContentType() == null || parsed.getContentType().isBlank()) {
                    parsed.setContentType(fallbackType);
                }
                fillTraceFields(parsed, snap, modelUsed);
                return parsed;
            }
            return fallback(fallbackType, "LLM_PARSE_FAILED", trim(raw), snap, modelUsed);
        } catch (Exception e) {
            log.warn("dashscope call failed", e);
            return fallback(fallbackType, "LLM_CALL_FAILED", trim(e.getMessage()), snap, modelUsed);
        }
    }

    private DashScopeChatOptions options(String model) {
        DashScopeChatOptions opts = new DashScopeChatOptions();
        if (model != null && !model.isBlank()) {
            opts.setModel(model);
        }
        // 风控场景尽量确定性输出
        opts.setTemperature(0D);
        return opts;
    }

    private RiskLlmResultVO parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String json = extractJsonObject(raw);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, RiskLlmResultVO.class);
        } catch (Exception e) {
            return null;
        }
    }

    private String extractJsonObject(String raw) {
        int l = raw.indexOf('{');
        int r = raw.lastIndexOf('}');
        if (l < 0 || r <= l) {
            return null;
        }
        return raw.substring(l, r + 1);
    }

    private String systemPrompt(String contentType, String scenario, String actionType, PromptSnapshot snap) {
        String sc = scenario == null ? "" : scenario;
        String at = actionType == null ? "" : actionType;
        String ct = contentType == null ? "" : contentType;
        String custom = snap == null ? null : snap.promptText;
        return ""
                + "You are a content risk-control model. Output JSON only; no natural language.\n"
                + "Required JSON fields: contentType,result,riskTags,confidence,reasonCode,evidence,suggestedAction.\n"
                + "Constraints: contentType in {TEXT,IMAGE}; result in {PASS,REVIEW,BLOCK}; suggestedAction in {ALLOW,QUARANTINE,BLOCK}.\n"
                + (custom == null || custom.isBlank() ? "" : ("\n" + custom.trim() + "\n"))
                + "Context: scenario=" + sc + ", actionType=" + at + ", contentType=" + ct + ".\n"
                + "Do not output any private or irrelevant content.";
    }

    private String firstUrl(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return null;
        }
        for (String u : urls) {
            if (u != null && !u.isBlank()) {
                return u;
            }
        }
        return null;
    }

    private RiskLlmResultVO fallback(String contentType, String reasonCode, String evidence, PromptSnapshot snap, String modelUsed) {
        RiskLlmResultVO res = RiskLlmResultVO.builder()
                .contentType(contentType)
                .result("REVIEW")
                .riskTags(List.of())
                .confidence(0D)
                .reasonCode(reasonCode)
                .evidence(evidence)
                .suggestedAction("QUARANTINE")
                .build();
        fillTraceFields(res, snap, modelUsed);
        return res;
    }

    private void fillTraceFields(RiskLlmResultVO res, PromptSnapshot snap, String modelUsed) {
        if (res == null) {
            return;
        }
        if (res.getPromptVersion() == null && snap != null) {
            res.setPromptVersion(snap.version);
        }
        if (res.getModel() == null || res.getModel().isBlank()) {
            res.setModel(modelUsed == null ? "" : modelUsed);
        }
    }

    private PromptSnapshot loadActivePrompt(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return new PromptSnapshot(null, null, null);
        }
        try {
            RiskPromptVersionEntity active = promptVersionRepository.findActive(contentType);
            if (active == null) {
                return new PromptSnapshot(null, null, null);
            }
            return new PromptSnapshot(active.getVersion(), active.getPromptText(), active.getModel());
        } catch (Exception e) {
            return new PromptSnapshot(null, null, null);
        }
    }

    private String chooseModel(String defaultModel, PromptSnapshot snap) {
        if (snap != null && snap.model != null && !snap.model.isBlank()) {
            return snap.model;
        }
        return defaultModel;
    }

    private static class PromptSnapshot {
        private final Long version;
        private final String promptText;
        private final String model;

        private PromptSnapshot(Long version, String promptText, String model) {
            this.version = version;
            this.promptText = promptText;
            this.model = model;
        }
    }

    private String trim(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
