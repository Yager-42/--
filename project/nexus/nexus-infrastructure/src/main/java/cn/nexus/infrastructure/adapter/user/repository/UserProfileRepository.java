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
 */
@Repository
public class UserProfileRepository implements IUserProfileRepository {

    private static final String KEY_USER_BASE = "social:userbase:";

    private final IUserBaseDao userBaseDao;
    private final RedisOperations<String, String> redisOps;

    @Autowired
    public UserProfileRepository(IUserBaseDao userBaseDao,
                                 @Qualifier("stringRedisTemplate") RedisOperations<String, String> redisOps) {
        this.userBaseDao = userBaseDao;
        this.redisOps = redisOps;
    }

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

    @Override
    public boolean updatePatch(Long userId, String nickname, String avatarUrl) {
        if (userId == null) {
            return false;
        }
        // 防御：即便调用方错误传入空 patch，也不要生成非法 SQL。
        if (nickname == null && avatarUrl == null) {
            return userBaseDao.selectByUserId(userId) != null;
        }

        int updated = userBaseDao.updatePatch(userId, nickname, avatarUrl);
        if (updated > 0) {
            evictUserBaseCache(userId);
            return true;
        }
        // MySQL affectedRows=0 可能意味着“值相同”，此处必须二次确认是否存在。
        return userBaseDao.selectByUserId(userId) != null;
    }

    private void evictUserBaseCache(Long userId) {
        try {
            redisOps.delete(KEY_USER_BASE + userId);
        } catch (Exception ignored) {
            // 缓存失效失败不应影响主流程
        }
    }
}
