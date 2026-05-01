package cn.nexus.api.social.counter.dto;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostCounterResponseDTO {

    private Long postId;
    private Map<String, Long> counts;
}
