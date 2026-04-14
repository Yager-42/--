package cn.nexus.integration;

import static org.assertj.core.api.Assertions.assertThat;

import cn.nexus.domain.social.model.valobj.UserBriefVO;
import cn.nexus.infrastructure.adapter.social.repository.UserBaseRepository;
import cn.nexus.infrastructure.dao.social.po.UserBasePO;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class UserBaseRepositoryRealIntegrationTest extends RealMiddlewareIntegrationTestSupport {

    @Autowired
    private UserBaseRepository userBaseRepository;

    @Test
    void listByUserIds_shouldLoadFromMysqlAndReuseRedisCache() {
        long userId = uniqueId();
        String redisKey = "social:userbase:" + userId;

        UserBasePO po = new UserBasePO();
        po.setUserId(userId);
        po.setUsername("real_user_" + userId);
        po.setNickname("昵称-" + userId);
        po.setAvatarUrl("https://avatar.example/" + userId + ".png");
        userBaseDao.insert(po);
        deleteRedisKey(redisKey);

        List<UserBriefVO> firstRead = userBaseRepository.listByUserIds(List.of(userId));

        assertThat(firstRead).hasSize(1);
        assertThat(firstRead.get(0).getUserId()).isEqualTo(userId);
        assertThat(firstRead.get(0).getNickname()).isEqualTo("昵称-" + userId);
        assertThat(stringRedisTemplate.opsForValue().get(redisKey)).contains("昵称-" + userId);

        userBaseDao.updatePatch(userId, "数据库新昵称-" + userId, null);

        List<UserBriefVO> secondRead = userBaseRepository.listByUserIds(List.of(userId));

        assertThat(secondRead).hasSize(1);
        assertThat(secondRead.get(0).getNickname()).isEqualTo("昵称-" + userId);
    }
}
