package cn.nexus.domain.social.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通用操作结果值对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OperationResultVO {
    private boolean success;
    private Long id;
    private Long attemptId;
    private Integer versionNum;
    private String status;
    private String message;
}
