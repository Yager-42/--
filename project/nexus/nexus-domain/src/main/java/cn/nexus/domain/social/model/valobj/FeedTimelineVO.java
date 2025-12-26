package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Feed 时间线。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeedTimelineVO {
    private List<FeedItemVO> items;
    private String nextCursor;
}
