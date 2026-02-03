package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.UserBasePO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IUserBaseDao {
    UserBasePO selectByUserId(@Param("userId") Long userId);

    List<UserBasePO> selectByUserIds(@Param("userIds") List<Long> userIds);

    List<UserBasePO> selectByUsernames(@Param("usernames") List<String> usernames);

    /**
     * Patch 更新：nickname/avatarUrl 传 null 表示不改；avatarUrl 允许传 "" 清空。
     *
     * <p>注意：MySQL affectedRows 可能为 0（值相同），调用方不得用它判断 NOT_FOUND。</p>
     */
    int updatePatch(@Param("userId") Long userId,
                    @Param("nickname") String nickname,
                    @Param("avatarUrl") String avatarUrl);
}
