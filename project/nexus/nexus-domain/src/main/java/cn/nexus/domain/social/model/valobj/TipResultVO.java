package cn.nexus.domain.social.model.valobj;

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
public class TipResultVO {
    private String txId;
    private String effectUrl;
}
