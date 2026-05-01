package cn.nexus.api.social.action.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionRequestDTO {

    private String targetType;
    private Long targetId;
    private String requestId;
}
