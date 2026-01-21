package cn.nexus.api.social.interaction.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 标记单条通知已读请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationReadRequestDTO {
    private Long notificationId;
}

