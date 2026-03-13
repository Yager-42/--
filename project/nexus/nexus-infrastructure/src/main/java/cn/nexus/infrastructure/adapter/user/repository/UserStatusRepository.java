package cn.nexus.infrastructure.adapter.user.repository;

import cn.nexus.domain.user.adapter.repository.IUserStatusRepository;
import cn.nexus.infrastructure.dao.user.IUserStatusDao;
import cn.nexus.infrastructure.dao.user.po.UserStatusPO;
import java.util.Date;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 用户状态仓储实现：`user_status` 不存在时视为 `ACTIVE`。
 *
 * <p>这样做的好处是读链路可以平滑兼容老数据，而写链路仍然能用最小状态把“已停用用户禁止修改资料”挡住。</p>
 *
 * @author rr
 * @author codex
 * @since 2026-02-03
 */
@Repository
@RequiredArgsConstructor
public class UserStatusRepository implements IUserStatusRepository {

    /**
     * 默认用户状态。
     */
    private static final String STATUS_ACTIVE = "ACTIVE";

    private final IUserStatusDao userStatusDao;

    /**
     * 查询用户状态。
     *
     * @param userId 用户 ID，类型：{@link Long}
     * @return 用户状态；没有记录时返回 {@code ACTIVE}，类型：{@link String}
     */
    @Override
    public String getStatus(Long userId) {
        if (userId == null) {
            return STATUS_ACTIVE;
        }
        UserStatusPO po = userStatusDao.selectByUserId(userId);
        if (po == null || po.getStatus() == null || po.getStatus().isBlank()) {
            return STATUS_ACTIVE;
        }
        return po.getStatus();
    }

    /**
     * 写入或更新用户状态。
     *
     * @param userId 用户 ID，类型：{@link Long}
     * @param status 用户状态，类型：{@link String}
     * @param deactivatedTimeMs 停用时间戳，未停用时传 {@code null}，类型：{@link Long}
     */
    @Override
    public void upsertStatus(Long userId, String status, Long deactivatedTimeMs) {
        if (userId == null || status == null || status.isBlank()) {
            return;
        }
        // 时间戳只在仓储边界转换成 `Date`，避免上层到处掺进存储细节。
        UserStatusPO po = new UserStatusPO();
        po.setUserId(userId);
        po.setStatus(status);
        po.setDeactivatedTime(deactivatedTimeMs == null ? null : new Date(deactivatedTimeMs));
        userStatusDao.upsert(po);
    }
}
