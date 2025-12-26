package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 投票创建结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PollCreateResultVO {
    private Long pollId;
}
