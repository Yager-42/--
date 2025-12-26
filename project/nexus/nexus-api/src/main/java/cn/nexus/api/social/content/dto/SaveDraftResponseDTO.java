package cn.nexus.api.social.content.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 草稿保存结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SaveDraftResponseDTO {
    private Long draftId;
}
