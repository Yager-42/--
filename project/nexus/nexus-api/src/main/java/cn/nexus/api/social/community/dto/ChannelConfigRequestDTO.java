package cn.nexus.api.social.community.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 频道配置请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChannelConfigRequestDTO {
    private Long channelId;
    private Integer slowModeInterval;
    private Boolean locked;
}
