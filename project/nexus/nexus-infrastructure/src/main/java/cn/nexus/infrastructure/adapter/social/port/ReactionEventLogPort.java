package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.IReactionEventLogPort;
import cn.nexus.domain.social.model.valobj.ReactionEventLogRecordVO;
import cn.nexus.infrastructure.adapter.social.repository.ReactionEventLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReactionEventLogPort implements IReactionEventLogPort {

    private final ReactionEventLogRepository repository;

    @Override
    public String append(ReactionEventLogRecordVO record) {
        if (record == null) {
            return "duplicate";
        }
        return repository.append(
                record.getEventId(),
                record.getTargetType(),
                record.getTargetId(),
                record.getReactionType(),
                record.getUserId(),
                record.getDesiredState() == null ? 0 : record.getDesiredState(),
                record.getDelta() == null ? 0 : record.getDelta(),
                record.getEventTime() == null ? 0L : record.getEventTime()
        );
    }
}
