package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 关系分组。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelationGroupVO {
    private Long listId;
    private String listName;
    private List<Long> memberIds;
}
