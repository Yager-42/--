package cn.nexus.domain.social.model.valobj;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelationStateBatchVO {
    private List<Long> followingUserIds;
    private List<Long> blockedUserIds;
}
