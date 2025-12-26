package cn.nexus.api.social.interaction.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 打赏结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TipResponseDTO {
    private String txId;
    private String effectUrl;
}
