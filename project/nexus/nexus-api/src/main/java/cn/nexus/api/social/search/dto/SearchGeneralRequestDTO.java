package cn.nexus.api.social.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 综合搜索请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchGeneralRequestDTO {
    private String keyword;
    private String type;
    private String sort;
    private String filters;
}
