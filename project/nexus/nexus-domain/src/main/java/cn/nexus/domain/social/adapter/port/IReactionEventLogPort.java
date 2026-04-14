package cn.nexus.domain.social.adapter.port;

import cn.nexus.domain.social.model.valobj.ReactionEventLogRecordVO;

public interface IReactionEventLogPort {

    String append(ReactionEventLogRecordVO record);
}
