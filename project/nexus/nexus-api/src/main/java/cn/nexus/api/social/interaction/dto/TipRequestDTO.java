package cn.nexus.api.social.interaction.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 打赏请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TipRequestDTO {
    private Long toUserId;
    private BigDecimal amount;
    private String currency;
    private Long postId;
}
