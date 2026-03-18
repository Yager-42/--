package cn.nexus.domain.auth.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录成功后的最小返回值。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthLoginResultVO {
    private Long userId;
    private String phone;
}
