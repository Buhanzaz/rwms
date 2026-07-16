package dev.buhanzaz.rwms.taskboard.eventing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "rwms.task-board.cutover.rehearsal",
    name = "enabled",
    havingValue = "true")
public class TaskBoardKafkaCutoverRehearsalRunner implements ApplicationRunner {
  private final TaskBoardKafkaCutoverRehearsal rehearsal;

  @Value("${rwms.task-board.cutover.rehearsal.write-freeze-confirmed:false}")
  private boolean writeFreezeConfirmed;

  @Value("${rwms.task-board.cutover.rehearsal.max-legacy-candidates:10000}")
  private int maxLegacyCandidates;

  @Override
  public void run(ApplicationArguments args) {
    var report =
        rehearsal.rehearse(
            new TaskBoardKafkaCutoverRehearsal.Request(
                writeFreezeConfirmed, maxLegacyCandidates));
    log.info(
        "Task-board Kafka cutover rehearsal completed "
            + "[legacyTotal={},legacyUnpublished={},legacyUnresolved={},mirrored={},retargeted={},unmappable={},"
            + "kafkaBacklog={},kafkaTerminalFailures={},publishedWithoutInbox={},"
            + "inboxWithoutPublished={},laggingAggregates={},openVersionGaps={},ready={}]",
        report.legacyTotal(),
        report.legacyUnpublished(),
        report.legacyUnresolved(),
        report.legacyAlreadyMirrored(),
        report.legacyRetargeted(),
        report.legacyUnmappable(),
        report.kafkaBacklog(),
        report.kafkaTerminalFailures(),
        report.publishedWithoutInbox(),
        report.inboxWithoutPublished(),
        report.laggingAggregates(),
        report.openVersionGaps(),
        report.ready());
    if (!report.ready()) {
      throw new IllegalStateException(
          "Task-board Kafka cutover rehearsal did not reach drain and parity readiness");
    }
  }
}
