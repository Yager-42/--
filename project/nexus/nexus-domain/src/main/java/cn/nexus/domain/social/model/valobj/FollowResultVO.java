package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 关注结果值对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FollowResultVO {
    private String status;
}
