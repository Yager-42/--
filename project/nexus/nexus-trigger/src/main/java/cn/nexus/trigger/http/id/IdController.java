package cn.nexus.trigger.http.id;

import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.infrastructure.adapter.id.LeafSegmentIdService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ID service endpoints.
 */
@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/id")
@RequiredArgsConstructor
public class IdController {

    private final LeafSegmentIdService leafSegmentIdService;
    private final ISocialIdPort socialIdPort;

    @GetMapping("/segment/get/{key}")
    public String segment(@PathVariable("key") String key) {
        long id = leafSegmentIdService.nextId(key);
        return String.valueOf(id);
    }

    @GetMapping("/snowflake/get/{key}")
    public String snowflake(@PathVariable("key") String key) {
        Long id = socialIdPort.nextId();
        return String.valueOf(id);
    }
}
