package cn.nexus.domain.system.model.valobj;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * 系统状态值对象，描述当前运行情况。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemStatusVO {
    /** 状态描述 */
    private String status;
}
