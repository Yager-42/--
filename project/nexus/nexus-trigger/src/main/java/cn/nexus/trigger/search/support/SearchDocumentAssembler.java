package cn.nexus.trigger.search.support;

import cn.nexus.domain.social.adapter.port.IMediaStoragePort;
import cn.nexus.domain.social.model.valobj.SearchDocumentVO;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SearchDocumentAssembler {

    private final IMediaStoragePort mediaStoragePort;

    public SearchDocumentVO assemble(Long contentId,
                                     Long authorId,
                                     String title,
                                     String description,
                                     String body,
                                     List<String> tags,
                                     String authorAvatar,
                                     String authorNickname,
                                     Long publishTime,
                                     Long likeCount,
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
                .likeCount(likeCount == null ? 0L : Math.max(0L, likeCount))
                .favoriteCount(0L)
                .viewCount(0L)
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
