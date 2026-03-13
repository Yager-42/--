package cn.nexus.api.social.interaction.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReactionLikerDTO {
    private Long userId;
    private String nickname;
    private String avatar;
    private Long likedAt;
}
