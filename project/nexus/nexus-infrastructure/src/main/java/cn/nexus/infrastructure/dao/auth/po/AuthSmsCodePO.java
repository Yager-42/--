package cn.nexus.infrastructure.dao.auth.po;

import java.util.Date;
import lombok.Data;

@Data
public class AuthSmsCodePO {
    private Long id;
    private String bizType;
    private String phone;
    private String codeHash;
    private Date expireAt;
    private Date usedAt;
    private Integer verifyFailCount;
    private String sendStatus;
    private String requestIp;
    private Integer latestFlag;
    private Date createTime;
}
