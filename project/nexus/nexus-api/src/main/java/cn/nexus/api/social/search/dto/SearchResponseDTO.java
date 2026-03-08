package cn.nexus.api.social.search.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResponseDTO {
    private List<SearchItemDTO> items;
    private String nextAfter;
    private boolean hasMore;
}
