package cn.nexus.infrastructure.dao.auth.po;

import java.util.Date;
import lombok.Data;

@Data
public class AuthUserRolePO {
    private Long id;
    private Long userId;
    private Long roleId;
    private Date createTime;
}
