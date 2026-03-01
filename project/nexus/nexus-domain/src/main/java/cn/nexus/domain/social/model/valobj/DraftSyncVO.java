package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 草稿同步结果值对象（仅承载对外返回需要的字段）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DraftSyncVO {
    /** 服务端接受并落库后的版本号（即 draft.clientVersion）。 */
    private Long serverVersion;
    /** 服务端落库时间（毫秒时间戳）。 */
    private Long syncTime;
}

