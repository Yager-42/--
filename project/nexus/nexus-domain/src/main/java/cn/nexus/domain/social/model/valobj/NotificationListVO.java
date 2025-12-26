package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 通知列表。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationListVO {
    private List<NotificationVO> notifications;
    private String nextCursor;
}
