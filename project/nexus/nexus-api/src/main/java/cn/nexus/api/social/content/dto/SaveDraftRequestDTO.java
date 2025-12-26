package cn.nexus.api.social.content.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 保存草稿请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SaveDraftRequestDTO {
    private Long userId;
    private String contentText;
    private List<String> mediaIds;
}
