package cn.nexus.infrastructure.adapter.auth.repository;

import cn.nexus.domain.auth.adapter.repository.IAuthUserBaseRepository;
import cn.nexus.domain.auth.model.valobj.AuthMeVO;
import cn.nexus.infrastructure.dao.social.IUserBaseDao;
import cn.nexus.infrastructure.dao.social.po.UserBasePO;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * 认证侧 user_base 仓储实现。
 */
@Repository
@RequiredArgsConstructor
public class AuthUserBaseRepository implements IAuthUserBaseRepository {

    private final IUserBaseDao userBaseDao;

    @Override
    public void create(Long userId, String username, String nickname, String avatarUrl) {
        UserBasePO po = new UserBasePO();
        po.setUserId(userId);
        po.setUsername(username);
        po.setNickname(nickname);
        po.setAvatarUrl(avatarUrl == null ? "" : avatarUrl);
        userBaseDao.insert(po);
    }

    @Override
    public AuthMeVO getMe(Long userId) {
        UserBasePO po = userBaseDao.selectByUserId(userId);
        if (po == null) {
            return null;
        }
        return toMe(po);
    }

    @Override
    public List<AuthMeVO> listByUserIds(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        List<UserBasePO> list = userBaseDao.selectByUserIds(userIds);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        return list.stream().map(this::toMe).toList();
    }

    private AuthMeVO toMe(UserBasePO po) {
        return AuthMeVO.builder()
                .userId(po.getUserId())
                .nickname(po.getNickname())
                .avatarUrl(po.getAvatarUrl())
                .build();
    }
}
