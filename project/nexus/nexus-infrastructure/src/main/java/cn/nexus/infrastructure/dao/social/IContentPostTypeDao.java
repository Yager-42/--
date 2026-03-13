package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.ContentPostTypePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 内容帖子类型映射表 DAO。
 *
 * <p>一条记录表示 postId 关联的一个业务类型（类目/主题）。</p>
 */
@Mapper
public interface IContentPostTypeDao {

    int deleteByPostId(@Param("postId") Long postId);

    int insertBatch(@Param("postId") Long postId, @Param("types") java.util.List<String> types);

    java.util.List<ContentPostTypePO> selectByPostIds(@Param("postIds") java.util.List<Long> postIds);
}

