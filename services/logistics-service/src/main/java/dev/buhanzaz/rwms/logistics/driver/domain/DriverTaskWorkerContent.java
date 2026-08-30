package dev.buhanzaz.rwms.logistics.driver.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Immutable logistics-owned native task presentation snapshot for one driver route entry.
 *
 * <p>The task-board stores this sanitized snapshot with the external task and delivers it through
 * its existing DriverApp and WorkerApp offline feeds. It is presentation data only: inventory and
 * route transitions remain owned by their existing aggregates.
 */
public record DriverTaskWorkerContent(
    String taskText,
    List<Work> works,
    List<Material> materials,
    List<Comment> comments,
    List<SourceMedia> sourceMedia) {
  public DriverTaskWorkerContent {
    taskText = optionalText(taskText, 2_000, "taskText");
    works = copy(works, 100, "works");
    materials = copy(materials, 100, "materials");
    comments = copy(comments, 100, "comments");
    sourceMedia = copy(sourceMedia, 100, "sourceMedia");
    java.util.Set<UUID> mediaIds = new java.util.LinkedHashSet<>();
    for (SourceMedia media : sourceMedia) {
      if (!mediaIds.add(media.mediaId())) {
        throw new IllegalArgumentException("Driver task source media identities must be unique");
      }
    }
    for (Work work : works) {
      if (!mediaIds.containsAll(work.sourceMediaIds())) {
        throw new IllegalArgumentException("Driver work references unknown source media");
      }
    }
  }

  /** Preserves source compatibility for tasks created before source gallery support. */
  public DriverTaskWorkerContent(
      String taskText, List<Work> works, List<Material> materials, List<Comment> comments) {
    this(taskText, works, materials, comments, List.of());
  }

  /** Returns an empty snapshot used by driver workflows that have no structured worker content. */
  public static DriverTaskWorkerContent empty() {
    return new DriverTaskWorkerContent(null, List.of(), List.of(), List.of(), List.of());
  }

  /** Returns whether this snapshot adds no content beyond the legacy route description. */
  @JsonIgnore
  public boolean isEmpty() {
    return taskText == null
        && works.isEmpty()
        && materials.isEmpty()
        && comments.isEmpty()
        && sourceMedia.isEmpty();
  }

  /** One ordered, immutable operation displayed inside the existing worker task. */
  public record Work(
      UUID id,
      String name,
      double quantity,
      String unit,
      Integer durationMinutes,
      String comment,
      List<UUID> sourceMediaIds) {
    public Work {
      if (id == null
          || !Double.isFinite(quantity)
          || quantity < 0
          || (durationMinutes != null && durationMinutes < 0)) {
        throw new IllegalArgumentException("Driver task work snapshot is invalid");
      }
      name = requiredText(name, 1_000, "work.name");
      unit = optionalText(unit, 32, "work.unit");
      comment = optionalText(comment, 2_000, "work.comment");
      sourceMediaIds = copy(sourceMediaIds, 100, "work.sourceMediaIds");
      if (sourceMediaIds.stream().distinct().count() != sourceMediaIds.size()) {
        throw new IllegalArgumentException("Work source media identities must be unique");
      }
    }

    /** Preserves source compatibility for work rows without gallery references. */
    public Work(
        UUID id,
        String name,
        double quantity,
        String unit,
        Integer durationMinutes,
        String comment) {
      this(id, name, quantity, unit, durationMinutes, comment, List.of());
    }
  }

  /** Immutable READY source photo reference resolved by task-board through its existing read path. */
  public record SourceMedia(
      UUID mediaId,
      long generation,
      String contentType,
      OffsetDateTime capturedAt,
      OffsetDateTime recordedAt) {
    public SourceMedia {
      if (mediaId == null || generation < 1 || recordedAt == null) {
        throw new IllegalArgumentException("Driver task source media snapshot is invalid");
      }
      contentType = optionalText(contentType, 128, "sourceMedia.contentType");
    }
  }

  /** One immutable cargo or equipment row displayed in the worker material manifest. */
  public record Material(UUID id, String name, double quantity, String unit) {
    public Material {
      if (id == null || !Double.isFinite(quantity) || quantity < 0) {
        throw new IllegalArgumentException("Driver task material snapshot is invalid");
      }
      name = requiredText(name, 1_000, "material.name");
      unit = optionalText(unit, 32, "material.unit");
    }
  }

  /** One immutable logistics comment visible to the assigned worker. */
  public record Comment(
      UUID id, String text, String authorDisplayName, OffsetDateTime createdAt) {
    public Comment {
      if (id == null || createdAt == null) {
        throw new IllegalArgumentException("Driver task comment snapshot is invalid");
      }
      text = requiredText(text, 2_000, "comment.text");
      authorDisplayName = optionalText(authorDisplayName, 256, "comment.authorDisplayName");
    }
  }

  private static <T> List<T> copy(List<T> values, int maximum, String field) {
    List<T> normalized = values == null ? List.of() : List.copyOf(values);
    if (normalized.size() > maximum || normalized.stream().anyMatch(value -> value == null)) {
      throw new IllegalArgumentException("Driver task " + field + " snapshot is invalid");
    }
    return normalized;
  }

  private static String requiredText(String value, int maximum, String field) {
    String normalized = optionalText(value, maximum, field);
    if (normalized == null) throw new IllegalArgumentException(field + " is required");
    return normalized;
  }

  private static String optionalText(String value, int maximum, String field) {
    if (value == null) return null;
    String normalized = value.trim();
    if (normalized.isEmpty() || normalized.length() > maximum) {
      throw new IllegalArgumentException(field + " is invalid");
    }
    return normalized;
  }
}
