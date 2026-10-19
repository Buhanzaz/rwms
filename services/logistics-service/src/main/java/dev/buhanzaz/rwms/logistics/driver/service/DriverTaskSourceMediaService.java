package dev.buhanzaz.rwms.logistics.driver.service;

import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskWorkerContent;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Reads and validates one bounded warehouse-scoped READY cabin gallery snapshot for driver tasks.
 *
 * <p>The result contains immutable media identities only. Task-board remains responsible for its
 * existing owner-proof and read-path resolution, so logistics never stores bytes or invents URLs.
 */
@Service
public class DriverTaskSourceMediaService {
  private final LogisticsDependencyGateway dependencies;

  /** Creates the source-gallery boundary over the canonical media dependency. */
  public DriverTaskSourceMediaService(LogisticsDependencyGateway dependencies) {
    this.dependencies = dependencies;
  }

  /**
   * Returns exact source-media rows and their cabin associations in requested cabin order.
   * Cabins without READY photos remain valid with an empty association.
   */
  public Snapshot read(UUID warehouseId, List<UUID> cabinIds, OffsetDateTime recordedAt) {
    if (warehouseId == null || cabinIds == null || recordedAt == null || cabinIds.size() > 100) {
      throw new IllegalArgumentException("Driver source-media request is invalid");
    }
    if (cabinIds.stream().anyMatch(java.util.Objects::isNull)) {
      throw new IllegalArgumentException("Driver source-media cabin identities must be unique");
    }
    List<UUID> requested = List.copyOf(cabinIds);
    Set<UUID> requestedIds = new LinkedHashSet<>(requested);
    if (requestedIds.size() != requested.size() || requestedIds.contains(null)) {
      throw new IllegalArgumentException("Driver source-media cabin identities must be unique");
    }
    if (requested.isEmpty()) return new Snapshot(Map.of(), List.of());

    List<LogisticsDependencyGateway.CabinMediaSnapshot> response =
        dependencies.readCabinMediaSnapshots(warehouseId, requested);
    if (response == null) {
      throw new LogisticsConflictException("Галерея бытовок недоступна");
    }
    Map<UUID, LogisticsDependencyGateway.CabinMediaSnapshot> byCabin = new LinkedHashMap<>();
    for (var item : response) {
      if (item == null
          || item.cabinId() == null
          || !requestedIds.contains(item.cabinId())
          || item.photoCount() < 0
          || item.photos() == null
          || byCabin.putIfAbsent(item.cabinId(), item) != null) {
        throw new LogisticsConflictException("Галерея вернула неверный набор бытовок");
      }
    }
    if (!byCabin.keySet().equals(requestedIds)) {
      throw new LogisticsConflictException("Галерея вернула неполный набор бытовок");
    }

    Map<UUID, List<UUID>> mediaByCabin = new LinkedHashMap<>();
    List<DriverTaskWorkerContent.SourceMedia> media = new ArrayList<>();
    Set<UUID> mediaIds = new LinkedHashSet<>();
    for (UUID cabinId : requested) {
      var item = byCabin.get(cabinId);
      List<UUID> cabinMediaIds = new ArrayList<>();
      if (item.photos().stream().anyMatch(java.util.Objects::isNull)) {
        throw new LogisticsConflictException("Галерея бытовок содержит неверную фотографию");
      }
      for (var photo : item.photos().stream()
          .sorted(
              Comparator.comparingInt(LogisticsDependencyGateway.CabinMediaPhoto::sortOrder)
                  .thenComparing(LogisticsDependencyGateway.CabinMediaPhoto::mediaId))
          .toList()) {
        if (photo.mediaId() == null
            || photo.generation() < 1
            || photo.sortOrder() < 0
            || photo.availableVariants() == null
            || !mediaIds.add(photo.mediaId())) {
          throw new LogisticsConflictException("Галерея бытовок содержит неверную фотографию");
        }
        cabinMediaIds.add(photo.mediaId());
        media.add(
            new DriverTaskWorkerContent.SourceMedia(
                photo.mediaId(), photo.generation(), null, null, recordedAt));
      }
      mediaByCabin.put(cabinId, List.copyOf(cabinMediaIds));
    }
    return new Snapshot(Map.copyOf(mediaByCabin), List.copyOf(media));
  }

  /** Ordered immutable result used to associate route operations with source photos. */
  public record Snapshot(
      Map<UUID, List<UUID>> mediaByCabin,
      List<DriverTaskWorkerContent.SourceMedia> sourceMedia) {
    public Snapshot {
      mediaByCabin = Map.copyOf(mediaByCabin);
      sourceMedia = List.copyOf(sourceMedia);
    }

    /** Returns the exact ordered photo identities for one cabin. */
    public List<UUID> mediaIds(UUID cabinId) {
      return mediaByCabin.getOrDefault(cabinId, List.of());
    }
  }
}
