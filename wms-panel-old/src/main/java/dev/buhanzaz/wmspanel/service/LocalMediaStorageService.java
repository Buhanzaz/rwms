package dev.buhanzaz.wmspanel.service;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;

@Service
public class LocalMediaStorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalMediaStorageService.class);
    private static final String UNKNOWN_SEGMENT = "_unknown";

    private final RepairMediaStorageProperties properties;
    private final Path root;
    private final MinioClient minioClient;

    public LocalMediaStorageService(RepairMediaStorageProperties properties) {
        this.properties = properties;
        this.root = Path.of(properties.getLocalRoot());
        this.minioClient = properties.isMinioConfigured()
                ? MinioClient.builder()
                .endpoint(properties.getMinioEndpoint())
                .credentials(properties.getMinioAccessKey(), properties.getMinioSecretKey())
                .build()
                : null;
    }

    public StoredMedia store(InputStream inputStream, String originalFileName) {
        return storeInternal(inputStream, originalFileName, null);
    }

    public StoredMedia store(InputStream inputStream, String originalFileName, StoragePathContext context) {
        return storeInternal(inputStream, originalFileName, context);
    }

    public StoredMedia storeIncoming(InputStream inputStream,
                                     String originalFileName,
                                     String contentType,
                                     StoragePathContext context,
                                     UUID photoId) {
        try {
            byte[] bytes = inputStream.readAllBytes();
            MediaFamilyPaths familyPaths = buildMediaFamilyPaths(context, photoId);
            Path target = root.resolve(familyPaths.incomingObjectKey());
            Path folder = target.getParent();
            if (folder != null) {
                Files.createDirectories(folder);
            }
            Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            uploadToMinio(familyPaths.incomingObjectKey(), bytes, contentType);
            return new StoredMedia(familyPaths.incomingObjectKey(), bytes.length, familyPaths.familyRootKey());
        } catch (IOException ex) {
            throw new IllegalStateException("Не удалось сохранить входящее фото", ex);
        }
    }

    private StoredMedia storeInternal(InputStream inputStream,
                                      String originalFileName,
                                      StoragePathContext context) {
        try {
            byte[] bytes = inputStream.readAllBytes();
            String safeFileName = sanitizeFileName(originalFileName);
            String objectPath = context == null
                    ? buildLegacyObjectPath(safeFileName)
                    : buildStructuredObjectPath(safeFileName, context);
            Path target = root.resolve(objectPath);
            Path folder = target.getParent();
            if (folder != null) {
                Files.createDirectories(folder);
            }
            Files.write(target, bytes);
            uploadToMinio(objectPath, bytes, "application/octet-stream");
            return new StoredMedia(objectPath, bytes.length, null);
        } catch (IOException ex) {
            throw new IllegalStateException("Не удалось сохранить файл", ex);
        }
    }

    public byte[] read(String relativePath) {
        if (minioClient != null) {
            try {
                return readFromMinio(relativePath);
            } catch (Exception ex) {
                log.warn("Не удалось прочитать медиа {} из MinIO, пробую локальный fallback", relativePath, ex);
            }
        }
        try {
            return Files.readAllBytes(root.resolve(relativePath));
        } catch (IOException ex) {
            throw new IllegalStateException("Не удалось прочитать файл", ex);
        }
    }

    public byte[] readIfExists(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }
        if (minioClient != null) {
            try {
                return readFromMinio(relativePath);
            } catch (Exception ignored) {
                // fallback to local
            }
        }
        try {
            Path localPath = root.resolve(relativePath);
            return Files.exists(localPath) ? Files.readAllBytes(localPath) : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    public void delete(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return;
        }
        deleteFromMinio(relativePath);
        try {
            Files.deleteIfExists(root.resolve(relativePath));
        } catch (IOException ex) {
            log.warn("Не удалось удалить локальное медиа {}", relativePath, ex);
        }
    }

    public void deleteIncoming(String familyRootKey) {
        if (familyRootKey == null || familyRootKey.isBlank()) {
            return;
        }
        delete(familyRootKey + "/" + sanitizeFileName(properties.getIncomingObjectName()));
    }

    public void deleteFamily(String familyRootKey) {
        if (familyRootKey == null || familyRootKey.isBlank()) {
            return;
        }
        deletePrefixFromMinio(familyRootKey);
        Path localRoot = root.resolve(familyRootKey);
        if (!Files.exists(localRoot)) {
            return;
        }
        try (var stream = Files.walk(localRoot)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ex) {
                            log.warn("Не удалось удалить локальный media prefix {}", path, ex);
                        }
                    });
        } catch (IOException ex) {
            log.warn("Не удалось удалить локальный family root {}", familyRootKey, ex);
        }
    }

    private String sanitizeFileName(String fileName) {
        return sanitizeFileNameValue(fileName, "upload.bin");
    }

    static String sanitizeFileNameValue(String value, String fallback) {
        return sanitizeValue(value, fallback, true);
    }

    static String sanitizePathSegmentValue(String value, String fallback) {
        return sanitizeValue(value, fallback, false);
    }

    private static String sanitizeValue(String value, String fallback, boolean allowDots) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        StringBuilder result = new StringBuilder();
        boolean lastWasSeparator = false;
        for (char ch : value.trim().toCharArray()) {
            boolean allowed = Character.isLetterOrDigit(ch)
                    || ch == '-'
                    || ch == '_'
                    || (allowDots && ch == '.');
            if (allowed) {
                result.append(ch);
                lastWasSeparator = false;
                continue;
            }
            if (Character.isWhitespace(ch) || ch == '/' || ch == '\\') {
                if (!lastWasSeparator && !result.isEmpty()) {
                    result.append('_');
                }
                lastWasSeparator = true;
                continue;
            }
            if (!lastWasSeparator && !result.isEmpty()) {
                result.append('_');
            }
            lastWasSeparator = true;
        }
        String sanitized = result.toString()
                .replaceAll("_+", "_")
                .replaceAll("^[_\\-.]+", "")
                .replaceAll("[_\\-.]+$", "");
        return sanitized.isBlank() ? fallback : sanitized;
    }

    private String buildLegacyObjectPath(String safeFileName) {
        LocalDate now = LocalDate.now();
        String prefix = trimSlashes(properties.getObjectPrefix());
        String fileName = UUID.randomUUID() + extensionOf(safeFileName);
        String yearMonthPath = now.getYear() + "/" + String.format("%02d", now.getMonthValue());
        if (prefix.isBlank()) {
            return yearMonthPath + "/" + fileName;
        }
        return prefix + "/" + yearMonthPath + "/" + fileName;
    }

    private String buildStructuredObjectPath(String safeFileName, StoragePathContext context) {
        LocalDate eventDate = context.eventDate() == null ? LocalDate.now() : context.eventDate().toLocalDate();
        String warehouseSegment = sanitizePathSegmentValue(firstNonBlank(context.warehouseCode(), context.warehouseCity()), UNKNOWN_SEGMENT);
        String itemSegment = sanitizePathSegmentValue(context.rentalItemNumber(), UNKNOWN_SEGMENT);
        String eventTypeSegment = sanitizeEventType(context.eventType());
        String eventIdSegment = context.eventId() == null ? UNKNOWN_SEGMENT : context.eventId().toString();
        String fileId = context.fileId() == null ? UUID.randomUUID().toString() : context.fileId().toString();
        String relativePath = String.join("/",
                "rental-items",
                warehouseSegment,
                itemSegment,
                eventTypeSegment,
                Integer.toString(eventDate.getYear()),
                String.format("%02d", eventDate.getMonthValue()),
                String.format("%02d", eventDate.getDayOfMonth()),
                eventIdSegment,
                fileId + "-" + safeFileName);
        String prefix = trimSlashes(properties.getObjectPrefix());
        if (prefix.isBlank()) {
            return relativePath;
        }
        return prefix + "/" + relativePath;
    }

    public MediaFamilyPaths buildMediaFamilyPaths(StoragePathContext context, UUID photoId) {
        String familyRoot = buildStructuredFamilyRoot(context, photoId);
        return new MediaFamilyPaths(
                familyRoot,
                familyRoot + "/" + sanitizeFileName(properties.getIncomingObjectName()),
                familyRoot + "/original.jpg"
        );
    }

    private String buildStructuredFamilyRoot(StoragePathContext context, UUID photoId) {
        LocalDate eventDate = context == null || context.eventDate() == null
                ? LocalDate.now()
                : context.eventDate().toLocalDate();
        String warehouseSegment = sanitizePathSegmentValue(context == null ? null : firstNonBlank(context.warehouseCode(), context.warehouseCity()), UNKNOWN_SEGMENT);
        String itemSegment = sanitizePathSegmentValue(context == null ? null : context.rentalItemNumber(), UNKNOWN_SEGMENT);
        String eventTypeSegment = sanitizeEventType(context == null ? null : context.eventType());
        String eventIdSegment = context == null || context.eventId() == null ? UNKNOWN_SEGMENT : context.eventId().toString();
        String photoIdSegment = photoId == null ? UUID.randomUUID().toString() : photoId.toString();
        String relativePath = String.join("/",
                "rental-items",
                warehouseSegment,
                itemSegment,
                eventTypeSegment,
                Integer.toString(eventDate.getYear()),
                String.format("%02d", eventDate.getMonthValue()),
                String.format("%02d", eventDate.getDayOfMonth()),
                eventIdSegment,
                photoIdSegment);
        String prefix = trimSlashes(properties.getObjectPrefix());
        if (prefix.isBlank()) {
            return relativePath;
        }
        return prefix + "/" + relativePath;
    }

    public String variantObjectKey(String familyRootKey, RepairMediaVariant variant, Integer longEdge) {
        if (familyRootKey == null || familyRootKey.isBlank()) {
            return null;
        }
        if (variant == null || variant == RepairMediaVariant.ORIGINAL) {
            return familyRootKey + "/original.jpg";
        }
        Integer size = longEdge == null || longEdge <= 0 ? defaultLongEdge(variant) : longEdge;
        return familyRootKey + "/" + variant.getId() + "_" + size + ".webp";
    }

    public Integer defaultLongEdge(RepairMediaVariant variant) {
        return switch (variant) {
            case PREVIEW -> properties.getPreviewLongEdge();
            case THUMB -> properties.getThumbLongEdge();
            case TINY -> properties.getTinyLongEdge();
            case ORIGINAL -> null;
        };
    }

    private String sanitizeEventType(String eventType) {
        String sanitized = sanitizePathSegmentValue(eventType, UNKNOWN_SEGMENT);
        if (UNKNOWN_SEGMENT.equals(sanitized)) {
            return sanitized;
        }
        return sanitized.replace('_', '-').toLowerCase(Locale.ROOT);
    }

    private String extensionOf(String fileName) {
        if (fileName == null) {
            return ".bin";
        }
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart < 0 || extensionStart == fileName.length() - 1) {
            return ".bin";
        }
        return fileName.substring(extensionStart);
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private void uploadToMinio(String objectPath, byte[] bytes, String contentType) {
        if (minioClient == null) {
            return;
        }
        try {
            ensureBucketExists();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getMinioBucket())
                    .object(objectPath)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType(contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType)
                    .build());
            log.info("Медиа {} сохранено в MinIO bucket {}", objectPath, properties.getMinioBucket());
        } catch (Exception ex) {
            log.warn("Не удалось сохранить медиа {} в MinIO, оставляю локальную копию", objectPath, ex);
        }
    }

    private byte[] readFromMinio(String objectPath) throws Exception {
        try (InputStream inputStream = minioClient.getObject(GetObjectArgs.builder()
                .bucket(properties.getMinioBucket())
                .object(objectPath)
                .build())) {
            return inputStream.readAllBytes();
        }
    }

    private void deleteFromMinio(String objectPath) {
        if (minioClient == null) {
            return;
        }
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.getMinioBucket())
                    .object(objectPath)
                    .build());
        } catch (Exception ex) {
            log.warn("Не удалось удалить медиа {} из MinIO", objectPath, ex);
        }
    }

    private void deletePrefixFromMinio(String prefix) {
        if (minioClient == null || prefix == null || prefix.isBlank()) {
            return;
        }
        try {
            Iterable<Result<Item>> objects = minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(properties.getMinioBucket())
                    .prefix(prefix.endsWith("/") ? prefix : prefix + "/")
                    .recursive(true)
                    .build());
            for (Result<Item> object : objects) {
                Item item = object.get();
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(properties.getMinioBucket())
                        .object(item.objectName())
                        .build());
            }
        } catch (Exception ex) {
            log.warn("Не удалось удалить media prefix {} из MinIO", prefix, ex);
        }
    }

    private void ensureBucketExists() throws Exception {
        if (!properties.isMinioAutoCreateBucket()) {
            return;
        }
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                .bucket(properties.getMinioBucket())
                .build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder()
                    .bucket(properties.getMinioBucket())
                    .build());
        }
    }

    private String trimSlashes(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "/").replaceAll("^/+", "").replaceAll("/+$", "");
    }

    public record StoredMedia(String relativePath, long sizeBytes, String familyRootKey) {
    }

    public record StoragePathContext(
            String warehouseCode,
            String warehouseCity,
            String rentalItemNumber,
            String eventType,
            OffsetDateTime eventDate,
            UUID eventId,
            UUID fileId
    ) {
    }

    public record MediaFamilyPaths(
            String familyRootKey,
            String incomingObjectKey,
            String canonicalOriginalObjectKey
    ) {
    }
}
