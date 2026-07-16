package dev.buhanzaz.wmspanel.service;

import dev.buhanzaz.wmspanel.entity.PhotoProcessingStatus;
import dev.buhanzaz.wmspanel.entity.RepairEstimate;
import dev.buhanzaz.wmspanel.entity.RepairEstimateLine;
import dev.buhanzaz.wmspanel.entity.RepairEstimateStatus;
import dev.buhanzaz.wmspanel.entity.RentalItem;
import dev.buhanzaz.wmspanel.entity.RentalItemEvent;
import dev.buhanzaz.wmspanel.entity.RentalItemEventPhoto;
import dev.buhanzaz.wmspanel.entity.RentalItemEventType;
import dev.buhanzaz.wmspanel.entity.RepairEstimateTaskPlan;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import io.jmix.core.DataManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class RepairEstimateService {

    private final DataManager dataManager;
    private final LocalMediaStorageService localMediaStorageService;
    private final RepairEstimateTaskPlanService repairEstimateTaskPlanService;
    private final RepairProcessService repairProcessService;
    private final RentalItemEventService rentalItemEventService;
    private final RentalItemService rentalItemService;
    private final PhotoProcessingQueueService photoProcessingQueueService;
    private final RepairMediaStorageProperties mediaStorageProperties;

    public RepairEstimateService(DataManager dataManager,
                                 LocalMediaStorageService localMediaStorageService,
                                 RepairEstimateTaskPlanService repairEstimateTaskPlanService,
                                 RepairProcessService repairProcessService,
                                 RentalItemEventService rentalItemEventService,
                                 RentalItemService rentalItemService,
                                 PhotoProcessingQueueService photoProcessingQueueService,
                                 RepairMediaStorageProperties mediaStorageProperties) {
        this.dataManager = dataManager;
        this.localMediaStorageService = localMediaStorageService;
        this.repairEstimateTaskPlanService = repairEstimateTaskPlanService;
        this.repairProcessService = repairProcessService;
        this.rentalItemEventService = rentalItemEventService;
        this.rentalItemService = rentalItemService;
        this.photoProcessingQueueService = photoProcessingQueueService;
        this.mediaStorageProperties = mediaStorageProperties;
    }

    public List<RepairEstimate> loadEstimates(UUID warehouseId, RepairEstimateStatus status) {
        List<RepairEstimate> estimates = dataManager.load(RepairEstimate.class)
                .query("""
                        select e from RepairEstimate e
                        where (:warehouseId is null or e.warehouse.id = :warehouseId)
                        order by e.createdDate desc
                        """)
                .parameter("warehouseId", warehouseId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("rentalItem", "_base")
                        .add("warehouse", "_base"))
                .list();
        if (status == null) {
            return estimates;
        }
        return estimates.stream()
                .filter(estimate -> estimate.getStatus() == status)
                .toList();
    }

    public RepairEstimate loadEstimate(UUID estimateId) {
        if (estimateId == null) {
            return null;
        }
        return dataManager.load(RepairEstimate.class)
                .id(estimateId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("rentalItem", "_base")
                        .add("warehouse", "_base")
                        .add("latestEvent", builder1 -> builder1.addFetchPlan("_base")
                                .add("repairProcess", "_base")
                                .add("boardTask", "_base")
                                .add("queueEntry", "_base")
                                .add("workerGroup", "_base")
                                .add("worker", "_base")))
                .optional()
                .orElse(null);
    }

    public List<RepairEstimateLine> loadLines(UUID estimateId) {
        return dataManager.load(RepairEstimateLine.class)
                .query("select e from RepairEstimateLine e where e.estimate.id = :estimateId order by e.rowOrder")
                .parameter("estimateId", estimateId)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("estimate", "_base")
                        .add("taskPlans", builder1 -> builder1.addFetchPlan("_base")
                                .add("repairProcess", "_base")
                                .add("queue", builder2 -> builder2.addFetchPlan("_base").add("warehouse", "_base"))
                                .add("followUpNode", "_base")
                                .add("generatedBoardTask", "_base")
                                .add("taskLines", builder2 -> builder2.addFetchPlan("_base").add("estimateLine", "_base"))))
                .list();
    }

    public List<RentalItemEvent> loadHistory(UUID rentalItemId) {
        return rentalItemEventService.loadTimeline(rentalItemId);
    }

    public List<RentalItemEventPhoto> loadPhotos(UUID eventId) {
        return rentalItemEventService.loadPhotos(eventId);
    }

    public RentalItemEvent latestEvent(UUID rentalItemId) {
        return rentalItemEventService.latestEvent(rentalItemId);
    }

    @Transactional
    public RepairEstimate saveEstimate(SaveEstimateCommand command) {
        RentalItem rentalItem = dataManager.load(RentalItem.class)
                .query("""
                        select e
                        from RentalItem e
                        left join fetch e.warehouse
                        where e.id = :rentalItemId
                        """)
                .parameter("rentalItemId", command.rentalItemId())
                .one();
        String rentalItemNumber = rentalItem.getNumber();
        UUID warehouseId = rentalItem.getWarehouse() == null ? null : rentalItem.getWarehouse().getId();
        RentalItem rentalItemRef = dataManager.getReference(RentalItem.class, command.rentalItemId());
        Warehouse warehouse = warehouseId == null ? null : dataManager.getReference(Warehouse.class, warehouseId);

        RepairEstimate estimate = command.estimateId() == null
                ? dataManager.create(RepairEstimate.class)
                : dataManager.load(RepairEstimate.class).id(command.estimateId()).one();

        estimate.setRentalItem(rentalItemRef);
        estimate.setWarehouse(warehouse);
        estimate.setCabinNumber(rentalItemNumber);
        estimate.setSourceParty(command.sourceParty());
        estimate.setDestinationParty(command.destinationParty());
        estimate.setComment(command.comment());
        estimate.setDispatchDate(command.dispatchDate());
        estimate.setStatus(command.status());

        estimate = dataManager.save(estimate);
        upsertLines(estimate, command.lines());
        rentalItemService.syncFurnitureEstimateAccessories(rentalItem, loadLines(estimate.getId()));

        RentalItemEvent event = command.status() == RepairEstimateStatus.DRAFT
                ? rentalItemEventService.recordEstimateDraft(estimate, command.comment(), command.dispatchDate())
                : rentalItemEventService.recordEstimateCompleted(estimate, false, command.comment(), command.dispatchDate());
        if (event != null) {
            RepairEstimate latestEstimate = dataManager.load(RepairEstimate.class)
                    .id(estimate.getId())
                    .one();
            latestEstimate.setLatestEvent(event);
            estimate = dataManager.save(latestEstimate);
        }

        if (event != null) {
            removePhotos(event.getId(), command.removedPhotoIds());

            int sortOrder = loadPhotos(event.getId()).size();
            for (UploadedPhoto photo : command.photos()) {
                RentalItemEventPhoto entity = dataManager.create(RentalItemEventPhoto.class);
                if (entity.getId() == null) {
                    entity.setId(UUID.randomUUID());
                }
                LocalMediaStorageService.StoredMedia storedMedia = localMediaStorageService.storeIncoming(
                        photo.inputStream(),
                        photo.fileName(),
                        photo.contentType(),
                        storagePathContext(event),
                        entity.getId()
                );
                entity.setEvent(event);
                entity.setStoragePath(storedMedia.relativePath());
                entity.setFamilyRootKey(storedMedia.familyRootKey());
                entity.setOriginalFileName(photo.fileName());
                entity.setContentType(photo.contentType());
                entity.setSizeBytes(storedMedia.sizeBytes());
                entity.setSortOrder(sortOrder++);
                entity.setProcessingStatus(photoProcessingQueueService.queueEnabled() ? PhotoProcessingStatus.PENDING : PhotoProcessingStatus.READY);
                dataManager.save(entity);
                enqueuePhotoProcessing(event, entity);
            }
        }

        return dataManager.load(RepairEstimate.class)
                .id(estimate.getId())
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("rentalItem", "_base")
                        .add("warehouse", "_base")
                        .add("latestEvent", "_base"))
                .one();
    }

    @Transactional
    public void deleteEstimate(UUID estimateId) {
        RepairEstimate estimate = dataManager.load(RepairEstimate.class).id(estimateId).one();
        if (estimate.getLatestEvent() != null && estimate.getLatestEvent().getId() != null) {
            deleteEventWithPhotos(estimate.getLatestEvent().getId());
        }
        repairEstimateTaskPlanService.deleteTaskPlans(estimateId);
        var process = repairProcessService.findByEstimateId(estimateId);
        List<RepairEstimateLine> lines = loadLines(estimateId);
        if (!lines.isEmpty()) {
            dataManager.remove(lines.toArray());
        }
        dataManager.remove(estimate);
        if (process != null) {
            dataManager.remove(process);
        }
    }

    private void upsertLines(RepairEstimate estimate, List<EstimateLineCommand> lines) {
        List<RepairEstimateLine> existing = loadLines(estimate.getId());
        java.util.Map<String, RepairEstimateLine> existingByKey = new java.util.LinkedHashMap<>();
        for (RepairEstimateLine line : existing) {
            String key = normalizeSourceLineKey(line.getSourceLineKey());
            if (key == null) {
                key = line.getId() == null ? UUID.randomUUID().toString() : line.getId().toString();
                line.setSourceLineKey(key);
            }
            existingByKey.put(key, line);
        }

        BigDecimal total = BigDecimal.ZERO;
        List<RepairEstimateLine> toSave = new ArrayList<>();
        int order = 0;
        for (EstimateLineCommand command : lines) {
            String sourceLineKey = normalizeSourceLineKey(command.sourceLineKey());
            if (sourceLineKey == null) {
                sourceLineKey = UUID.randomUUID().toString();
            }
            RepairEstimateLine line = existingByKey.remove(sourceLineKey);
            if (line == null) {
                line = dataManager.create(RepairEstimateLine.class);
            }
            line.setEstimate(estimate);
            line.setSourceLineKey(sourceLineKey);
            line.setLineType(command.lineType());
            line.setDescription(command.description());
            line.setLineComment(command.lineComment());
            line.setUnit(command.unit());
            line.setQuantity(command.quantity());
            line.setUnitPrice(command.unitPrice());
            BigDecimal lineTotal = command.unitPrice().multiply(BigDecimal.valueOf(command.quantity()));
            line.setLineTotal(lineTotal);
            line.setRowOrder(order++);
            line.setCatalogCode(command.catalogCode());
            total = total.add(lineTotal);
            toSave.add(line);
        }
        if (!existingByKey.isEmpty()) {
            List<RepairEstimateLine> removable = new ArrayList<>();
            for (RepairEstimateLine line : existingByKey.values()) {
                if (line.getTaskPlans() == null || line.getTaskPlans().isEmpty()) {
                    removable.add(line);
                } else {
                    total = total.add(line.getLineTotal() == null ? BigDecimal.ZERO : line.getLineTotal());
                    toSave.add(line);
                }
            }
            if (!removable.isEmpty()) {
                dataManager.remove(removable.toArray());
            }
        }
        if (!toSave.isEmpty()) {
            dataManager.save(toSave.toArray());
        }
        estimate.setTotalAmount(total);
        dataManager.save(estimate);
    }

    private void removePhotos(UUID eventId, Set<UUID> removedPhotoIds) {
        if (eventId == null || removedPhotoIds == null || removedPhotoIds.isEmpty()) {
            return;
        }
        List<RentalItemEventPhoto> photosToRemove = dataManager.load(RentalItemEventPhoto.class)
                .query("""
                        select e from RentalItemEventPhoto e
                        where e.event.id = :eventId
                          and e.id in :photoIds
                        """)
                .parameter("eventId", eventId)
                .parameter("photoIds", removedPhotoIds)
                .list();
        if (!photosToRemove.isEmpty()) {
            photosToRemove.forEach(this::deletePhotoMedia);
            dataManager.remove(photosToRemove.toArray());
        }
    }

    private void deleteEventWithPhotos(UUID eventId) {
        if (eventId == null) {
            return;
        }
        List<RentalItemEventPhoto> photos = dataManager.load(RentalItemEventPhoto.class)
                .query("select e from RentalItemEventPhoto e where e.event.id = :eventId")
                .parameter("eventId", eventId)
                .list();
        photos.forEach(this::deletePhotoMedia);
        if (!photos.isEmpty()) {
            dataManager.remove(photos.toArray());
        }
        dataManager.load(RentalItemEvent.class)
                .id(eventId)
                .optional()
                .ifPresent(dataManager::remove);
    }

    public List<HistoryEntry> historyEntries(UUID rentalItemId) {
        return loadHistory(rentalItemId).stream()
                .map(event -> new HistoryEntry(
                        event.getId(),
                        event.getTitle(),
                        event.getEventType(),
                        event.getEventDate(),
                        event.getComment(),
                        event.getEstimate() == null ? null : event.getEstimate().getId(),
                        loadPhotos(event.getId()).size()))
                .sorted(Comparator.comparing(HistoryEntry::eventDate).reversed())
                .toList();
    }

    public String mediaUrl(UUID photoId) {
        return mediaUrl(photoId, PhotoVariant.PREVIEW);
    }

    public String mediaUrl(UUID photoId, PhotoVariant variant) {
        PhotoVariant resolvedVariant = variant == null ? PhotoVariant.PREVIEW : variant;
        return "/api/repair-media/" + photoId + "?variant=" + resolvedVariant.urlValue();
    }

    public StoredPhotoContent loadPhotoContent(UUID photoId) {
        return loadPhotoContent(photoId, PhotoVariant.PREVIEW);
    }

    public StoredPhotoContent loadPhotoContent(UUID photoId, PhotoVariant variant) {
        RentalItemEventPhoto photo = dataManager.load(RentalItemEventPhoto.class).id(photoId).one();
        VariantBinary binary = loadVariantBinary(photo, variant == null ? PhotoVariant.PREVIEW : variant);
        return new StoredPhotoContent(binary.contentType(), binary.bytes());
    }

    public StoredPhotoArchive loadLatestPhotoArchive(UUID rentalItemId) {
        if (rentalItemId == null) {
            return null;
        }
        RentalItem rentalItem = dataManager.load(RentalItem.class)
                .id(rentalItemId)
                .fetchPlan(builder -> builder.addFetchPlan("_base").add("warehouse", "_base"))
                .optional()
                .orElse(null);
        if (rentalItem == null) {
            return null;
        }
        List<RentalItemEventPhoto> latestPhotos = rentalItemEventService.loadLatestPhotos(rentalItemId);
        if (latestPhotos.isEmpty()) {
            return null;
        }

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            Set<String> usedEntryNames = new HashSet<>();
            int index = 1;
            for (RentalItemEventPhoto photo : latestPhotos) {
                String entryName = buildArchiveEntryName(rentalItem, index++, photo, usedEntryNames);
                zipOutputStream.putNextEntry(new ZipEntry(entryName));
                zipOutputStream.write(loadVariantBinary(photo, PhotoVariant.ORIGINAL).bytes());
                zipOutputStream.closeEntry();
            }
            zipOutputStream.finish();
            return new StoredPhotoArchive(buildArchiveFileName(rentalItem), outputStream.toByteArray(), latestPhotos.size());
        } catch (IOException ex) {
            throw new IllegalStateException("Не удалось сформировать архив последних фото", ex);
        }
    }

    public record SaveEstimateCommand(
            UUID estimateId,
            UUID rentalItemId,
            String sourceParty,
            String destinationParty,
            String comment,
            OffsetDateTime dispatchDate,
            dev.buhanzaz.wmspanel.entity.RepairEstimateStatus status,
            List<EstimateLineCommand> lines,
            List<UploadedPhoto> photos,
            Set<UUID> removedPhotoIds
    ) {
        public SaveEstimateCommand(
                UUID estimateId,
                UUID rentalItemId,
                String sourceParty,
                String destinationParty,
                String comment,
                OffsetDateTime dispatchDate,
                dev.buhanzaz.wmspanel.entity.RepairEstimateStatus status,
                List<EstimateLineCommand> lines,
                List<UploadedPhoto> photos
        ) {
            this(estimateId, rentalItemId, sourceParty, destinationParty, comment, dispatchDate, status, lines, photos, Set.of());
        }
    }

    public record EstimateLineCommand(
            String sourceLineKey,
            dev.buhanzaz.wmspanel.entity.RepairEstimateLineType lineType,
            String description,
            String lineComment,
            String unit,
            Integer quantity,
            BigDecimal unitPrice,
            String catalogCode
    ) {
        public EstimateLineCommand {
            Objects.requireNonNull(lineType);
            Objects.requireNonNull(description);
            Objects.requireNonNull(unit);
            Objects.requireNonNull(quantity);
            Objects.requireNonNull(unitPrice);
        }
    }

    public record UploadedPhoto(String fileName, String contentType, InputStream inputStream) {
    }

    public record HistoryEntry(
            UUID eventId,
            String title,
            dev.buhanzaz.wmspanel.entity.RentalItemEventType eventType,
            OffsetDateTime eventDate,
            String comment,
            UUID estimateId,
            int photoCount
    ) {
    }

    public record StoredPhotoContent(String contentType, byte[] bytes) {
    }

    public record StoredPhotoArchive(String fileName, byte[] bytes, int photoCount) {
    }

    private record VariantBinary(String contentType, byte[] bytes) {
    }

    public enum PhotoVariant {
        THUMB("thumb"),
        PREVIEW("preview"),
        TINY("tiny"),
        ORIGINAL("original");

        private final String urlValue;

        PhotoVariant(String urlValue) {
            this.urlValue = urlValue;
        }

        public String urlValue() {
            return urlValue;
        }

        public static PhotoVariant fromUrlValue(String value) {
            if (value == null || value.isBlank()) {
                return PREVIEW;
            }
            for (PhotoVariant variant : values()) {
                if (variant.urlValue.equalsIgnoreCase(value)) {
                    return variant;
                }
            }
            return PREVIEW;
        }
    }

    private String normalizeSourceLineKey(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private LocalMediaStorageService.StoragePathContext storagePathContext(RentalItemEvent event) {
        return new LocalMediaStorageService.StoragePathContext(
                event == null || event.getWarehouse() == null ? null : event.getWarehouse().getCode(),
                event == null || event.getWarehouse() == null ? null : event.getWarehouse().getCity(),
                event == null || event.getRentalItem() == null ? null : event.getRentalItem().getNumber(),
                event == null || event.getEventType() == null ? null : event.getEventType().getId(),
                event == null ? null : event.getEventDate(),
                event == null ? null : event.getId(),
                null
        );
    }

    private String buildArchiveFileName(RentalItem rentalItem) {
        String itemNumber = LocalMediaStorageService.sanitizePathSegmentValue(
                rentalItem == null ? null : rentalItem.getNumber(),
                "rental-item"
        );
        return itemNumber + "-latest-photos.zip";
    }

    private String buildArchiveEntryName(RentalItem rentalItem,
                                         int index,
                                         RentalItemEventPhoto photo,
                                         Set<String> usedEntryNames) {
        String itemNumber = LocalMediaStorageService.sanitizePathSegmentValue(
                rentalItem == null ? null : rentalItem.getNumber(),
                "rental-item"
        );
        String originalFileName = LocalMediaStorageService.sanitizeFileNameValue(
                photo == null ? null : photo.getOriginalFileName(),
                "photo.bin"
        );
        String baseName = itemNumber + "-" + String.format("%02d", index) + "-" + originalFileName;
        String uniqueName = baseName;
        int duplicateCounter = 2;
        while (!usedEntryNames.add(uniqueName)) {
            uniqueName = itemNumber + "-" + String.format("%02d", index) + "-" + duplicateCounter + "-" + originalFileName;
            duplicateCounter++;
        }
        return uniqueName;
    }

    private void enqueuePhotoProcessing(RentalItemEvent event, RentalItemEventPhoto photo) {
        if (!photoProcessingQueueService.queueEnabled() || event == null || photo == null) {
            return;
        }
        try {
            photoProcessingQueueService.publish(new PhotoProcessingTaskMessage(
                    photo.getId(),
                    mediaStorageProperties.getMinioBucket(),
                    photo.getStoragePath(),
                    photo.getFamilyRootKey(),
                    photo.getContentType(),
                    event.getRentalItem() == null ? null : event.getRentalItem().getId(),
                    event.getId(),
                    event.getWarehouse() == null ? null : event.getWarehouse().getCode(),
                    event.getRentalItem() == null ? null : event.getRentalItem().getNumber(),
                    event.getEventType() == null ? null : event.getEventType().getId(),
                    mediaStorageProperties.getPreviewLongEdge(),
                    mediaStorageProperties.getThumbLongEdge(),
                    mediaStorageProperties.getTinyLongEdge()
            ));
        } catch (RuntimeException ex) {
            photoProcessingQueueService.markFailed(photo.getId(), ex.getMessage());
        }
    }

    private VariantBinary loadVariantBinary(RentalItemEventPhoto photo, PhotoVariant variant) {
        if (photo == null) {
            throw new IllegalStateException("Фото не найдено");
        }
        if (variant == PhotoVariant.ORIGINAL) {
            byte[] bytes = localMediaStorageService.read(photo.getStoragePath());
            String type = photo.getProcessingStatus() == PhotoProcessingStatus.READY ? "image/jpeg" : photo.getContentType();
            return new VariantBinary(type == null || type.isBlank() ? "application/octet-stream" : type, bytes);
        }

        byte[] preferredBytes = readVariant(photo, variant);
        if (preferredBytes != null) {
            return new VariantBinary("image/webp", preferredBytes);
        }
        if (variant == PhotoVariant.THUMB) {
            byte[] previewBytes = readVariant(photo, PhotoVariant.PREVIEW);
            if (previewBytes != null) {
                return new VariantBinary("image/webp", previewBytes);
            }
        }
        byte[] originalBytes = localMediaStorageService.read(photo.getStoragePath());
        String originalType = photo.getProcessingStatus() == PhotoProcessingStatus.READY ? "image/jpeg" : photo.getContentType();
        return new VariantBinary(originalType == null || originalType.isBlank() ? "application/octet-stream" : originalType, originalBytes);
    }

    private byte[] readVariant(RentalItemEventPhoto photo, PhotoVariant variant) {
        String familyRootKey = photo.getFamilyRootKey();
        if (familyRootKey == null || familyRootKey.isBlank()) {
            return null;
        }
        Integer longEdge = switch (variant) {
            case PREVIEW -> variantLongEdge(photo.getPreviewWidth(), photo.getPreviewHeight());
            case THUMB -> variantLongEdge(photo.getThumbWidth(), photo.getThumbHeight());
            case TINY -> variantLongEdge(photo.getTinyWidth(), photo.getTinyHeight());
            case ORIGINAL -> null;
        };
        String objectKey = localMediaStorageService.variantObjectKey(
                familyRootKey,
                RepairMediaVariant.from(variant.urlValue()),
                longEdge
        );
        return localMediaStorageService.readIfExists(objectKey);
    }

    private Integer variantLongEdge(Integer width, Integer height) {
        if (width == null && height == null) {
            return null;
        }
        if (width == null) {
            return height;
        }
        if (height == null) {
            return width;
        }
        return Math.max(width, height);
    }

    private void deletePhotoMedia(RentalItemEventPhoto photo) {
        if (photo == null) {
            return;
        }
        if (photo.getFamilyRootKey() != null && !photo.getFamilyRootKey().isBlank()) {
            localMediaStorageService.deleteFamily(photo.getFamilyRootKey());
            return;
        }
        if (photo.getStoragePath() != null && !photo.getStoragePath().isBlank()) {
            localMediaStorageService.delete(photo.getStoragePath());
        }
    }
}
