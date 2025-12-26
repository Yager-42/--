package cn.nexus.api.social.feed.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Feed 响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedTimelineResponseDTO {
    private List<FeedItemDTO> items;
    private String nextCursor;
}
