package cn.nexus.api.social.relation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 关注分组结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelationGroupResponseDTO {
    private Long listId;
    private String listName;
    private List<Long> memberIds;
}
