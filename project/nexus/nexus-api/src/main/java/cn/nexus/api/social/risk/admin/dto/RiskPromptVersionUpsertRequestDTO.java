package cn.nexus.api.social.risk.admin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 创建/更新 Prompt 版本请求：保存 prompt_text 并生成/更新版本号。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskPromptVersionUpsertRequestDTO {
    /** 可选：指定 version 表示更新该 DRAFT 版本；为空表示创建新版本 */
    private Long version;
    /** TEXT/IMAGE（必填） */
    private String contentType;
    /** System Prompt 文本（必填） */
    private String promptText;
    /** 可选：绑定模型名（便于回溯） */
    private String model;
}

