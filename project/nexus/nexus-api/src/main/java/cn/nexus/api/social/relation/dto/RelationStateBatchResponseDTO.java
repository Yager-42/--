package cn.nexus.api.social.relation.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelationStateBatchResponseDTO {
    private List<Long> followingUserIds;
    private List<Long> blockedUserIds;
}
