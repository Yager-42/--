package cn.nexus.api.social.relation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 关注分组管理。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelationGroupRequestDTO {
    private Long userId;
    private String action;
    private String listName;
    private Long listId;
    private List<Long> memberIds;
}
