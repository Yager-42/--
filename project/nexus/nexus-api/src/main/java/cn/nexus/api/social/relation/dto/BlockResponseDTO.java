package cn.nexus.api.social.relation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 屏蔽结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BlockResponseDTO {
    private boolean success;
}
