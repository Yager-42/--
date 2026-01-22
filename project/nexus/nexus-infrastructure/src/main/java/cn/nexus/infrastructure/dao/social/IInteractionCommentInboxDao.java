package cn.nexus.infrastructure.dao.social;

import cn.nexus.infrastructure.dao.social.po.InteractionCommentInboxPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface IInteractionCommentInboxDao {

    int insertIgnore(InteractionCommentInboxPO po);
}

