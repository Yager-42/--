package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 草稿信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DraftVO {
    private Long draftId;
}
