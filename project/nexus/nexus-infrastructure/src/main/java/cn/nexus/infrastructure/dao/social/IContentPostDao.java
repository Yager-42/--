package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.ContentPostPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IContentPostDao {

    int insert(ContentPostPO po);

    ContentPostPO selectById(@Param("postId") Long postId);

    int updateStatus(@Param("postId") Long postId, @Param("status") Integer status);

    int updateStatusWithUser(@Param("postId") Long postId, @Param("userId") Long userId, @Param("status") Integer status);

    int updateContentAndVersion(@Param("postId") Long postId,
                                @Param("contentText") String contentText,
                                @Param("mediaInfo") String mediaInfo,
                                @Param("locationInfo") String locationInfo,
                                @Param("versionNum") Integer versionNum,
                                @Param("isEdited") Integer isEdited,
                                @Param("status") Integer status,
                                @Param("visibility") Integer visibility);
}
