package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.model.valobj.OperationResultVO;
import cn.nexus.domain.social.model.valobj.SearchResultVO;
import cn.nexus.domain.social.model.valobj.SearchSuggestVO;
import cn.nexus.domain.social.model.valobj.SearchTrendingVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 搜索服务实现。
 */
@Service
@RequiredArgsConstructor
public class SearchService implements ISearchService {

    private final ISocialIdPort socialIdPort;

    @Override
    public SearchResultVO search(String keyword, String type, String sort, String filters) {
        SearchResultVO.SearchItemVO item = SearchResultVO.SearchItemVO.builder()
                .id(String.valueOf(socialIdPort.nextId()))
                .type(type == null ? "POST" : type)
                .title(keyword == null ? "示例" : keyword)
                .summary("占位搜索结果")
                .build();
        return SearchResultVO.builder()
                .items(List.of(item))
                .facets("demo")
                .build();
    }

    @Override
    public SearchSuggestVO suggest(String keyword) {
        return SearchSuggestVO.builder()
                .suggestions(List.of(keyword, keyword + " 热搜"))
                .build();
    }

    @Override
    public SearchTrendingVO trending(String category) {
        return SearchTrendingVO.builder()
                .keywords(List.of("热门1", "热门2"))
                .build();
    }

    @Override
    public OperationResultVO clearHistory(Long userId) {
        return OperationResultVO.builder()
                .success(true)
                .id(userId)
                .status("CLEARED")
                .message("搜索历史已清空")
                .build();
    }
}
