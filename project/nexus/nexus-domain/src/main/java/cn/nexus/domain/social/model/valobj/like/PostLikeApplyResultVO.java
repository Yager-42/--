package cn.nexus.domain.social.model.valobj.like;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostLikeApplyResultVO {
    /**
     * 0=already, 1=applied, 2=need db check.
     */
    private Integer status;
    private Integer delta;
    private Long currentCount;
}
