package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 关系边读模型：用户 + 关系时间。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelationUserEdgeVO {
    private Long userId;
    private Long followTimeMs;
}
