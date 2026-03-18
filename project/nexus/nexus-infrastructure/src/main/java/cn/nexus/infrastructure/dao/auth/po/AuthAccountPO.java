package cn.nexus.infrastructure.dao.auth.po;

import java.util.Date;
import lombok.Data;

@Data
public class AuthAccountPO {
    private Long accountId;
    private Long userId;
    private String phone;
    private String passwordHash;
    private Date passwordUpdatedAt;
    private Date lastLoginAt;
    private Date createTime;
    private Date updateTime;
}
