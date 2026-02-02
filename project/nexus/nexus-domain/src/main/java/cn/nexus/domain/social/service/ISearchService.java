package cn.nexus.domain.social.service;

import cn.nexus.domain.social.model.valobj.OperationResultVO;
import cn.nexus.domain.social.model.valobj.SearchResultVO;
import cn.nexus.domain.social.model.valobj.SearchSuggestVO;
import cn.nexus.domain.social.model.valobj.SearchTrendingVO;

/**
 * 搜索服务。
 */
public interface ISearchService {

    SearchResultVO search(Long userId, String keyword, String type, String sort, String filters);

    SearchSuggestVO suggest(Long userId, String keyword);

    SearchTrendingVO trending(String category);

    OperationResultVO clearHistory(Long userId);
}
