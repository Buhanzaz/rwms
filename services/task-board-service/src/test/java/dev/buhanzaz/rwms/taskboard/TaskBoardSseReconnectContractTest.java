package dev.buhanzaz.rwms.taskboard;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.taskboard.api.DriverTaskBoardController;
import dev.buhanzaz.rwms.taskboard.api.WorkerTaskBoardController;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RequestHeader;

/** Guards the invalidation-only SSE contract against accidental cursor replay promises. */
class TaskBoardSseReconnectContractTest {

  @Test
  void streamControllersAcceptOnlyTheAuthenticatedPrincipal() {
    for (Class<?> controller :
        List.of(WorkerTaskBoardController.class, DriverTaskBoardController.class)) {
      Method events =
          Arrays.stream(controller.getDeclaredMethods())
              .filter(method -> method.getName().equals("events"))
              .findFirst()
              .orElseThrow();

      assertThat(events.getParameterTypes()).containsExactly(Jwt.class);
      assertThat(events.getParameterAnnotations()[0])
          .anyMatch(annotation -> annotation.annotationType().equals(AuthenticationPrincipal.class));
      assertThat(
              Arrays.stream(events.getParameterAnnotations())
                  .flatMap(Stream::of)
                  .anyMatch(annotation -> annotation.annotationType().equals(RequestHeader.class)))
          .isFalse();
    }
  }

  @Test
  void publicContractDescribesFreshReconnectsWithoutAReplayHeader() throws Exception {
    Path contract =
        Path.of(
            System.getProperty("rwms.contracts.dir"), "openapi", "task-board-service.yaml");
    String yaml = Files.readString(contract);

    for (String streamPath : List.of("/worker/v1/events:", "/driver/v1/events:")) {
      int start = yaml.indexOf(streamPath);
      int end = yaml.indexOf("\n  /", start + streamPath.length());
      assertThat(start).isGreaterThanOrEqualTo(0);
      assertThat(end).isGreaterThan(start);
      String operation = yaml.substring(start, end);
      assertThat(operation).doesNotContain("Last-Event-ID");
      assertThat(operation).contains("authoritative REST");
    }
  }
}
