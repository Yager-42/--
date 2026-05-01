package cn.nexus.domain.social.model.valobj;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchDocumentVO {
    private Long contentId;
    private String contentType;
    private String title;
    private String description;
    private String body;
    private List<String> tags;
    private Long authorId;
    private String authorAvatar;
    private String authorNickname;
    private String authorTagJson;
    private Long publishTime;
    private String status;
    private List<String> imgUrls;
    private Boolean isTop;
    private String titleSuggest;
}
