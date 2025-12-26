package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通知值对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationVO {
    private String title;
    private String content;
    private Long createTime;
}
