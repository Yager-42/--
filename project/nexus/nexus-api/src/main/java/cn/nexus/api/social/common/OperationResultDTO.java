package cn.nexus.api.social.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通用操作结果，便于复用简单成功响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OperationResultDTO {
    /** 是否成功 */
    private boolean success;
    /** 关联主键或任务ID，可选 */
    private Long id;
    /** 状态描述 */
    private String status;
    /** 提示信息 */
    private String message;
}
