package dev.buhanzaz.rwms.taskboard.api;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;
import static dev.buhanzaz.rwms.taskboard.api.WorkerApiModels.WorkerFeedEntry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.buhanzaz.rwms.taskboard.domain.EntryStatus;
import dev.buhanzaz.rwms.taskboard.domain.EntryType;
import dev.buhanzaz.rwms.taskboard.domain.QueuePurpose;
import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import dev.buhanzaz.rwms.taskboard.domain.TaskLane;
import dev.buhanzaz.rwms.taskboard.domain.TaskStatus;
import dev.buhanzaz.rwms.taskboard.security.WarehouseAccessAuthorizer;
import dev.buhanzaz.rwms.taskboard.service.TaskBoardService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.context.request.ServletWebRequest;
import tools.jackson.databind.json.JsonMapper;

class TaskBoardControllerConditionalGetTest {
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000000401");
  private static final UUID QUEUE = UUID.fromString("00000000-0000-0000-0000-000000000402");
  private static final UUID TASK = UUID.fromString("00000000-0000-0000-0000-000000000403");
  private static final UUID ENTRY = UUID.fromString("00000000-0000-0000-0000-000000000404");
  private static final LocalDate DATE = LocalDate.of(2026, 7, 30);
  private static final Jwt JWT =
      Jwt.withTokenValue("task-board-conditional-get-token")
          .header("alg", "none")
          .subject("00000000-0000-0000-0000-000000000405")
          .build();

  private final TaskBoardService service = mock(TaskBoardService.class);
  private final WarehouseAccessAuthorizer access = mock(WarehouseAccessAuthorizer.class);
  private TaskBoardController controller;

  @BeforeEach
  void setUp() {
    controller =
        new TaskBoardController(service, access, JsonMapper.builder().findAndAddModules().build());
  }

  @Test
  void weakEtagIgnoresRollingTimerValuesAndHonorsListedIfNoneMatch() {
    when(service.snapshot(WAREHOUSE))
        .thenReturn(snapshot(120, "2026-07-30T10:00:00Z", TimerState.WORKING))
        .thenReturn(snapshot(121, "2026-07-30T10:00:01Z", TimerState.WORKING));

    ResponseEntity<TaskBoardSnapshot> initial = get(null);

    assertThat(initial.getStatusCode()).isEqualTo(HttpStatus.OK);
    String etag = initial.getHeaders().getETag();
    assertThat(etag).startsWith("W/\"task-board-");

    ResponseEntity<TaskBoardSnapshot> notModified = get("\"obsolete\", " + etag);

    assertThat(notModified.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    assertThat(notModified.getHeaders().getETag()).isEqualTo(etag);
    assertThat(notModified.getBody()).isNull();
  }

  @Test
  void weakEtagIncludesStableTimerState() {
    TaskBoardSnapshot working = snapshot(120, "2026-07-30T10:00:00Z", TimerState.WORKING);
    when(service.snapshot(WAREHOUSE))
        .thenReturn(working)
        .thenReturn(snapshot(120, "2026-07-30T10:00:00Z", TimerState.PAUSED));

    String standard = get(null).getHeaders().getETag();
    String paused = get(null).getHeaders().getETag();

    assertThat(paused).isNotEqualTo(standard);
  }

  @Test
  void ordinaryBoardAndRegistrationDtosAlwaysSerializeTheNullableAudienceField() {
    JsonMapper mapper =
        JsonMapper.builder()
            .findAndAddModules()
            .changeDefaultPropertyInclusion(
                value -> value.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();
    var boardJson = mapper.valueToTree(snapshot(120, "2026-07-30T10:00:00Z", TimerState.WORKING));
    var entryJson = boardJson.required("columns").get(0).required("entries").get(0);
    assertThat(entryJson.has("driverAudience")).isTrue();
    assertThat(entryJson.required("driverAudience").isNull()).isTrue();

    var registration =
        new BoardTaskRegistrationDto(
            TASK,
            3,
            WAREHOUSE,
            null,
            "Покраска",
            "БТ-1",
            null,
            TaskStatus.ACTIVE,
            10,
            null,
            DATE,
            TaskLane.SCHEDULED,
            3,
            false,
            null,
            null,
            List.of());
    var registrationJson = mapper.valueToTree(registration);
    assertThat(registrationJson.has("driverAudience")).isTrue();
    assertThat(registrationJson.required("driverAudience").isNull()).isTrue();

    var feedEntry =
        new WorkerFeedEntry(
            ENTRY,
            2,
            TASK,
            0,
            1,
            "Покраска",
            "БТ-1",
            "Покраска суриком",
            DATE,
            null,
            3,
            0,
            "WAITING",
            "AVAILABLE",
            null,
            10,
            null,
            0,
            null,
            List.of(),
            0,
            1);
    var feedJson = mapper.valueToTree(feedEntry);
    assertThat(feedJson.required("routeStepCount").asInt()).isOne();
    assertThat(feedJson.has("driverAudience")).isTrue();
    assertThat(feedJson.required("driverAudience").isNull()).isTrue();
  }

  private ResponseEntity<TaskBoardSnapshot> get(String ifNoneMatch) {
    MockHttpServletRequest request =
        new MockHttpServletRequest("GET", "/api/warehouses/" + WAREHOUSE + "/task-board");
    if (ifNoneMatch != null) {
      request.addHeader(HttpHeaders.IF_NONE_MATCH, ifNoneMatch);
    }
    return controller.snapshot(
        JWT, WAREHOUSE, new ServletWebRequest(request, new MockHttpServletResponse()));
  }

  private TaskBoardSnapshot snapshot(long countedActiveSeconds, String serverTime, TimerState timerState) {
    OffsetDateTime activeStartedAt = OffsetDateTime.of(2026, 7, 30, 9, 58, 0, 0, ZoneOffset.UTC);
    TaskTimerSnapshot timer =
        new TaskTimerSnapshot(
            countedActiveSeconds,
            600 - countedActiveSeconds,
            new BigDecimal("80.00"),
            timerState,
            OffsetDateTime.of(2026, 7, 30, 12, 0, 0, 0, ZoneOffset.UTC),
            OffsetDateTime.parse(serverTime));
    BoardEntryDto entry =
        new BoardEntryDto(
            ENTRY,
            2,
            TASK,
            null,
            3,
            "Покраска",
            "БТ-1",
            TaskStatus.ACTIVE,
            DATE,
            TaskLane.SCHEDULED,
            3,
            false,
            QUEUE,
            QueuePurpose.GENERAL,
            0,
            0,
            EntryType.REAL,
            EntryStatus.IN_PROGRESS,
            "Покраска суриком",
            10,
            activeStartedAt,
            null,
            0,
            List.of(),
            timer,
            null,
            null);
    return new TaskBoardSnapshot(
        WAREHOUSE,
        List.of(
            new BoardColumnDto(
                QUEUE,
                "Ремонт",
                QueueType.REPAIR,
                QueuePurpose.GENERAL,
                0,
                6,
                List.of(entry))));
  }
}
