package cn.nexus.infrastructure.dao.auth.po;

import java.util.Date;
import lombok.Data;

@Data
public class AuthRolePO {
    private Long roleId;
    private String roleCode;
    private String roleName;
    private Date createTime;
    private Date updateTime;
}
