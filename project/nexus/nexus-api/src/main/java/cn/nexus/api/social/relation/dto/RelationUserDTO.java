package cn.nexus.api.social.relation.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelationUserDTO {
    private Long userId;
    private String nickname;
    private String avatar;
    private Long followTime;
}
