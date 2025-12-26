package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 用户风控状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserRiskStatusVO {
    private String status;
    private List<String> capabilities;
}
