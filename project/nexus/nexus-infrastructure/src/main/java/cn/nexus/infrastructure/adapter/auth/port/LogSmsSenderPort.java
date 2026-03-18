package cn.nexus.infrastructure.adapter.auth.port;

import cn.nexus.domain.auth.adapter.port.ISmsSenderPort;
import cn.nexus.domain.auth.model.valobj.AuthSmsBizTypeVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 短信发送占位实现。
 */
@Slf4j
@Component
public class LogSmsSenderPort implements ISmsSenderPort {

    @Override
    public boolean send(String phone, String code, AuthSmsBizTypeVO bizType) {
        if (phone == null || phone.isBlank() || code == null || code.isBlank() || bizType == null) {
            return false;
        }
        log.info("mock sms sent, phone={}, bizType={}, code={}", phone, bizType, code);
        return true;
    }
}
