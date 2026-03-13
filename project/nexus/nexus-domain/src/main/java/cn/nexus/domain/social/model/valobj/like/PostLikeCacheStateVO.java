package cn.nexus.domain.social.model.valobj.like;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Cache-only state snapshot.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostLikeCacheStateVO {
    /**
     * liked=null means unknown (need DB check).
     */
    private Boolean liked;

    /**
     * currentCount from near-realtime counter key.
     */
    private Long currentCount;
}
