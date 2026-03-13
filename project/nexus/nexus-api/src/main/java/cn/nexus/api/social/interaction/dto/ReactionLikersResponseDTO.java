package cn.nexus.api.social.interaction.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReactionLikersResponseDTO {
    private List<ReactionLikerDTO> items;
    private String nextCursor;
}
