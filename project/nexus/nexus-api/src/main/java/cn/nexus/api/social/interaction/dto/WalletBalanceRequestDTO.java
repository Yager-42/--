package cn.nexus.api.social.interaction.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 钱包余额查询请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletBalanceRequestDTO {
    private String currencyType;
}
