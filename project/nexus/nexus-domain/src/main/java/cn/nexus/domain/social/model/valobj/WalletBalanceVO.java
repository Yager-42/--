package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 钱包余额。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletBalanceVO {
    private String currencyType;
    private String amount;
    private String frozenAmount;
}
