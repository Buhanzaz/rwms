package dev.buhanzaz.rwms.taskboard.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Public manager-facing transport models for immutable worker problem reports. */
public final class ProblemReportApiModels {
  private ProblemReportApiModels() {}

  /** Scalar report fields mapped from the report aggregate before evidence rows are joined. */
  public record TaskProblemReportSummary(
      UUID reportId,
      UUID entryId,
      UUID taskId,
      UUID warehouseId,
      UUID workerId,
      String workerName,
      String entryTitle,
      int routeIndex,
      String comment,
      OffsetDateTime occurredAt,
      OffsetDateTime recordedAt) {}

  /** One report attachment without exposing unrelated worker-task evidence through the board API. */
  public record TaskProblemReportAttachment(
      UUID evidenceId,
      String state,
      OffsetDateTime capturedAt,
      OffsetDateTime recordedAt,
      UUID mediaId,
      Long mediaGeneration,
      String reviewReason,
      String contentType,
      String readPath,
      String thumbnailPath) {}

  /** A manager-visible report plus the requesting manager's personal read marker. */
  public record TaskProblemReport(
      UUID reportId,
      UUID entryId,
      UUID taskId,
      int routeIndex,
      String entryTitle,
      String workerName,
      String comment,
      OffsetDateTime occurredAt,
      OffsetDateTime recordedAt,
      OffsetDateTime readAt,
      List<TaskProblemReportAttachment> attachments,
      List<TaskRequirementApiModels.MissingItem> missingItems, String unitNumber,
      boolean appliedToAll) {}

  /** Bounded newest-first page with the requester's full unread count. */
  public record TaskProblemReportPage(
      List<TaskProblemReport> reports, String nextCursor, int unreadCount) {}
}
