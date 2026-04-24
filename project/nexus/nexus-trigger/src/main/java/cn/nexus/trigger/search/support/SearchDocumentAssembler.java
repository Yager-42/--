package cn.nexus.trigger.search.support;

import cn.nexus.domain.social.adapter.port.IMediaStoragePort;
import cn.nexus.domain.social.model.valobj.SearchDocumentVO;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * SearchDocumentAssembler 实现。
 *
 * @author m0_52354773
 * @author codex
 * @since 2026-03-09
 */
@Component
@RequiredArgsConstructor
public class SearchDocumentAssembler {

    private final IMediaStoragePort mediaStoragePort;

    /**
     * 执行 assemble 逻辑。
     *
     * @param contentId contentId 参数。类型：{@link Long}
     * @param authorId authorId 参数。类型：{@link Long}
     * @param title title 参数。类型：{@link String}
     * @param description description 参数。类型：{@link String}
     * @param body body 参数。类型：{@link String}
     * @param tags tags 参数。类型：{@link List}
     * @param authorAvatar authorAvatar 参数。类型：{@link String}
     * @param authorNickname authorNickname 参数。类型：{@link String}
     * @param publishTime publishTime 参数。类型：{@link Long}
     * @param mediaInfo mediaInfo 参数。类型：{@link String}
     * @return 处理结果。类型：{@link SearchDocumentVO}
     */
    public SearchDocumentVO assemble(Long contentId,
                                     Long authorId,
                                     String title,
                                     String description,
                                     String body,
                                     List<String> tags,
                                     String authorAvatar,
                                     String authorNickname,
                                     Long publishTime,
                                     String mediaInfo) {
        return SearchDocumentVO.builder()
                .contentId(contentId)
                .contentType("POST")
                .title(title)
                .description(description)
                .body(body == null ? "" : body)
                .tags(tags == null ? List.of() : tags)
                .authorId(authorId)
                .authorAvatar(authorAvatar)
                .authorNickname(authorNickname)
                .authorTagJson(null)
                .publishTime(publishTime)
                .status("published")
                .imgUrls(resolveImgUrls(mediaInfo))
                .isTop(null)
                .titleSuggest(title)
                .build();
    }

    private List<String> resolveImgUrls(String mediaInfo) {
        if (mediaInfo == null || mediaInfo.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : mediaInfo.split(",")) {
            String token = part == null ? "" : part.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (token.startsWith("http://") || token.startsWith("https://")) {
                result.add(token);
                continue;
            }
            String readUrl = mediaStoragePort.generateReadUrl(token);
            result.add(readUrl == null || readUrl.isBlank() ? token : readUrl);
        }
        return result;
    }
}
