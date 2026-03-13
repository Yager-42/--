package cn.nexus.infrastructure.adapter.user.repository;

import cn.nexus.domain.user.adapter.repository.IUserProfileRepository;
import cn.nexus.domain.user.model.valobj.UserProfileVO;
import cn.nexus.infrastructure.dao.social.IUserBaseDao;
import cn.nexus.infrastructure.dao.social.po.UserBasePO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.stereotype.Repository;

/**
 * 用户 Profile 仓储实现：复用 user_base 作为真值源。
 *
 * @author rr
 * @author codex
 * @since 2026-02-03
 */
@Repository
public class UserProfileRepository implements IUserProfileRepository {

    /**
     * 用户基础缓存 Key 前缀。
     */
    private static final String KEY_USER_BASE = "social:userbase:";

    private final IUserBaseDao userBaseDao;
    private final RedisOperations<String, String> redisOps;

    /**
     * 创建用户 Profile 仓储。
     *
     * @param userBaseDao 用户基础表 DAO，类型：{@link IUserBaseDao}
     * @param redisOps Redis 操作对象，类型：{@link RedisOperations}
     */
    @Autowired
    public UserProfileRepository(IUserBaseDao userBaseDao,
                                 @Qualifier("stringRedisTemplate") RedisOperations<String, String> redisOps) {
        this.userBaseDao = userBaseDao;
        this.redisOps = redisOps;
    }

    /**
     * 读取用户 Profile 真值。
     *
     * @param userId 用户 ID，类型：{@link Long}
     * @return 用户资料，不存在时返回 {@code null}，类型：{@link UserProfileVO}
     */
    @Override
    public UserProfileVO get(Long userId) {
        if (userId == null) {
            return null;
        }
        UserBasePO po = userBaseDao.selectByUserId(userId);
        if (po == null) {
            return null;
        }
        return UserProfileVO.builder()
                .userId(po.getUserId())
                .username(po.getUsername())
                .nickname(po.getNickname())
                .avatarUrl(po.getAvatarUrl())
                .build();
    }

    /**
     * Patch 更新用户资料。
     *
     * @param userId 用户 ID，类型：{@link Long}
     * @param nickname 新昵称，为 {@code null} 时表示不改，类型：{@link String}
     * @param avatarUrl 新头像地址，为 {@code null} 时表示不改，类型：{@link String}
     * @return 用户存在时返回 {@code true}，用户不存在时返回 {@code false}，类型：{@code boolean}
     */
    @Override
    public boolean updatePatch(Long userId, String nickname, String avatarUrl) {
        if (userId == null) {
            return false;
        }
        // 防御性分支：空 Patch 不下发 SQL，只回答“用户存不存在”。
        if (nickname == null && avatarUrl == null) {
            return userBaseDao.selectByUserId(userId) != null;
        }

        int updated = userBaseDao.updatePatch(userId, nickname, avatarUrl);
        if (updated > 0) {
            evictUserBaseCache(userId);
            return true;
        }
        // `affectedRows = 0` 既可能是“值没变”，也可能是“用户不存在”，必须回表区分。
        return userBaseDao.selectByUserId(userId) != null;
    }

    private void evictUserBaseCache(Long userId) {
        try {
            redisOps.delete(KEY_USER_BASE + userId);
        } catch (Exception ignored) {
            // 缓存失效只是优化，不应该反过来拖垮主写链路。
        }
    }
}
