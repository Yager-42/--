package cn.nexus.domain.social.model.valobj;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 搜索索引文档（本次只覆盖 POST）。
 *
 * <p>注意：所有字段允许为空，但 entityIdStr 必须非空（用于精确匹配）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchDocumentVO {

    /** 用于按 ID 精确搜索（例：String.valueOf(postId)）。 */
    private String entityIdStr;

    /** 发布时间毫秒时间戳（语义=发布时间；字段名沿用 createTimeMs）。 */
    private Long createTimeMs;

    // POST 专属字段
    private Long postId;
    private Long authorId;
    private String authorNickname;
    private String contentText;
    private List<String> postTypes;
    private Integer mediaType;
}

