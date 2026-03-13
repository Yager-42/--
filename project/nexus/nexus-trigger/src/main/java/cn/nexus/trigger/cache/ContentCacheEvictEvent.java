package cn.nexus.trigger.cache;

import cn.nexus.types.event.BaseEvent;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ContentCacheEvictEvent extends BaseEvent {
    private Long postId;
}
