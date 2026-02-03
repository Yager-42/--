package cn.nexus.domain.user.adapter.repository;

/**
 * 用户状态仓储：最小状态 ACTIVE/DEACTIVATED。
 *
 * <p>约定：不存在记录视为 ACTIVE（只拦写，不拦读）。</p>
 */
public interface IUserStatusRepository {

    /**
     * 查询用户状态；不存在返回 ACTIVE。
     */
    String getStatus(Long userId);

    /**
     * Upsert 用户状态。
     *
     * @param userId            用户 ID
     * @param status            ACTIVE/DEACTIVATED
     * @param deactivatedTimeMs 若 status=DEACTIVATED，可传停用时间；否则传 null
     */
    void upsertStatus(Long userId, String status, Long deactivatedTimeMs);
}

