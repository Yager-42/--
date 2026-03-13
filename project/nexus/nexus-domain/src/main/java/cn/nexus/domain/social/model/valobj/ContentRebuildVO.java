package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文本重建结果。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ContentRebuildVO {
    private String contentText;
    private Integer versionNum;
}
