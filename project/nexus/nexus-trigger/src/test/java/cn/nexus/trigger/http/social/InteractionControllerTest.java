package cn.nexus.trigger.http.social;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class InteractionControllerTest {

    @Test
    void interactionControllerShouldNotExposeReactionCounterRoutes() {
        Set<String> routes = java.util.Arrays.stream(InteractionController.class.getDeclaredMethods())
                .flatMap(method -> java.util.stream.Stream.concat(
                        java.util.Arrays.stream(method.getAnnotationsByType(PostMapping.class))
                                .flatMap(mapping -> java.util.Arrays.stream(mapping.value())),
                        java.util.Arrays.stream(method.getAnnotationsByType(GetMapping.class))
                                .flatMap(mapping -> java.util.Arrays.stream(mapping.value()))))
                .collect(Collectors.toSet());
        String prefix = InteractionController.class.getAnnotation(RequestMapping.class).value()[0];

        assertFalse(routes.contains("/interact/reaction"));
        assertFalse(routes.contains("/interact/reaction/state"));
        assertFalse(routes.stream().map(route -> prefix + route)
                .anyMatch(route -> route.equals("/api/v1/interact/reaction")
                        || route.equals("/api/v1/interact/reaction/state")));
    }
}
