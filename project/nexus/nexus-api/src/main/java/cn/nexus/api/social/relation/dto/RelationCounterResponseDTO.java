package cn.nexus.api.social.relation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Public relation counter response aligned with the zhiguang naming contract.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelationCounterResponseDTO {
    private long followings;
    private long followers;
    private long posts;
    private long likesReceived;
    private long favsReceived;
}
