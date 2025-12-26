package cn.nexus.api.social.interaction.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通知列表请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationListRequestDTO {
    private Long userId;
    private String cursor;
}
