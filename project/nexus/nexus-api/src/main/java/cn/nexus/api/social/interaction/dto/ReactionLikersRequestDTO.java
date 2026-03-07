package cn.nexus.api.social.interaction.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReactionLikersRequestDTO {
    private Long targetId;
    private String targetType;
    private String type;
    private String cursor;
    private Integer limit;
}
