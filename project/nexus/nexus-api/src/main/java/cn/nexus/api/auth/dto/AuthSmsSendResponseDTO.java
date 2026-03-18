package cn.nexus.api.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 发送短信验证码响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthSmsSendResponseDTO {
    private Integer expireSeconds;
}
