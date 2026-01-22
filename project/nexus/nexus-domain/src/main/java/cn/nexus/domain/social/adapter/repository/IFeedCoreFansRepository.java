package cn.nexus.domain.social.adapter.repository;

import java.util.List;

/**
 * Feed 铁粉仓储接口：用于“大 V 写扩散降级”时，只推送给铁粉集合（Redis SET）。
 *
 * <p>铁粉集合的生成策略可以演进；本接口提供写入/判断/查询的最小契约（写入幂等）。</p>
 *
 * @author codex
 * @since 2026-01-14
 */
public interface IFeedCoreFansRepository {

    /**
     * 将某个用户加入作者的铁粉集合（幂等）。
     *
     * @param authorId   作者用户 ID {@link Long}
     * @param followerId 粉丝用户 ID {@link Long}
     */
    void addCoreFan(Long authorId, Long followerId);

    /**
     * 判断某个粉丝是否属于某个作者的铁粉集合。
     *
     * @param authorId   作者用户 ID {@link Long}
     * @param followerId 粉丝用户 ID {@link Long}
     * @return true=是铁粉，false=不是 {@code boolean}
     */
    boolean isCoreFan(Long authorId, Long followerId);

    /**
     * 列出某个作者的铁粉集合（数量受限）。
     *
     * @param authorId 作者用户 ID {@link Long}
     * @param limit    最大返回数量 {@code int}
     * @return 铁粉用户 ID 列表 {@link List} {@link Long}
     */
    List<Long> listCoreFans(Long authorId, int limit);
}
