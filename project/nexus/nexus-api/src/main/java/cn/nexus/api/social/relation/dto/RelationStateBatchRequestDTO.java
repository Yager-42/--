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
public class RelationStateBatchRequestDTO {
    private List<Long> targetUserIds;
}
