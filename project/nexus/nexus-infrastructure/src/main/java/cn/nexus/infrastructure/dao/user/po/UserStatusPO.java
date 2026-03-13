package cn.nexus.infrastructure.dao.user.po;

import java.util.Date;
import lombok.Data;

/**
 * 用户状态 PO。
 */
@Data
public class UserStatusPO {
    private Long userId;
    private String status;
    private Date deactivatedTime;
    private Date updateTime;
}

