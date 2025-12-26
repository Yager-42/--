package cn.nexus.api.social.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 清空历史请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchHistoryDeleteRequestDTO {
    private Long userId;
}
