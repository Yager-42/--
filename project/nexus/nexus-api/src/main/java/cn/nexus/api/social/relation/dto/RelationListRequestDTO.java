package cn.nexus.api.social.relation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelationListRequestDTO {
    private Long userId;
    private String cursor;
    private Integer limit;
}
