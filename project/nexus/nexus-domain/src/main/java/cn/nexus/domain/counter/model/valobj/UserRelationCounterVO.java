package cn.nexus.domain.counter.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 关系计数公共读模型（对齐 zhiguang 命名）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserRelationCounterVO {
    private long followings;
    private long followers;
    private long posts;
    private long likedPosts;
}
