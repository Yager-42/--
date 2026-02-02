package cn.nexus.domain.social.adapter.port;

import cn.nexus.domain.social.model.valobj.SearchDocumentVO;
import cn.nexus.domain.social.model.valobj.SearchEngineQueryVO;
import cn.nexus.domain.social.model.valobj.SearchEngineResultVO;

/**
 * 搜索引擎端口：封装 Elasticsearch 的查询/写入能力（本次只覆盖 POST）。
 *
 * <p>领域层只依赖端口，不关心底层是 ES 还是其它实现。</p>
 */
public interface ISearchEnginePort {

    /**
     * 执行搜索查询。
     *
     * @param query 已归一化后的查询参数
     * @return 搜索结果（包含 hits 与可选聚合）
     */
    SearchEngineResultVO search(SearchEngineQueryVO query);

    /**
     * Upsert 索引文档（幂等：文档 ID 由实现按规则生成）。
     *
     * @param doc 索引文档
     */
    void upsert(SearchDocumentVO doc);

    /**
     * 删除索引文档（幂等）。
     *
     * @param docId 文档 ID（格式：POST:{postId}）
     */
    void delete(String docId);

    /**
     * 批量更新作者昵称（用于 user.nickname_changed）。
     *
     * @param authorId       作者用户 ID
     * @param authorNickname 新昵称（允许为空字符串）
     * @return 影响文档数（0 也算成功）
     */
    long updateAuthorNickname(Long authorId, String authorNickname);
}

