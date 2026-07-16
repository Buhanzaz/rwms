package dev.buhanzaz.wmspanel.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LocalMediaStorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void storesAndReadsRealTestImageWithLocalFallback() throws Exception {
        Path testImage = Path.of("testImage", "IMG_1918.JPG");
        assertThat(testImage).exists();
        assertThat(Files.size(testImage)).isGreaterThan(1_000_000L);

        RepairMediaStorageProperties properties = new RepairMediaStorageProperties();
        properties.setLocalRoot(tempDir.toString());
        properties.setMinioEnabled(false);
        LocalMediaStorageService service = new LocalMediaStorageService(properties);

        LocalMediaStorageService.StoredMedia stored;
        try (InputStream inputStream = Files.newInputStream(testImage)) {
            stored = service.store(inputStream, "IMG_1918.JPG");
        }

        assertThat(stored.relativePath()).endsWith(".JPG");
        assertThat(stored.sizeBytes()).isEqualTo(Files.size(testImage));
        assertThat(tempDir.resolve(stored.relativePath())).exists();
        assertThat(service.read(stored.relativePath())).isEqualTo(Files.readAllBytes(testImage));

        service.delete(stored.relativePath());
        assertThat(tempDir.resolve(stored.relativePath())).doesNotExist();
    }

    @Test
    void storesStructuredRentalItemPathWhenContextIsProvided() throws Exception {
        RepairMediaStorageProperties properties = new RepairMediaStorageProperties();
        properties.setLocalRoot(tempDir.toString());
        properties.setObjectPrefix("repair-estimates");
        properties.setMinioEnabled(false);
        LocalMediaStorageService service = new LocalMediaStorageService(properties);

        UUID eventId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID fileId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        LocalMediaStorageService.StoragePathContext context = new LocalMediaStorageService.StoragePathContext(
                "MSK-1",
                null,
                "AB 12/34",
                "ESTIMATE_DRAFT",
                OffsetDateTime.parse("2026-06-28T10:15:30+03:00"),
                eventId,
                fileId
        );

        LocalMediaStorageService.StoredMedia stored;
        try (InputStream inputStream = new ByteArrayInputStream("hello".getBytes())) {
            stored = service.store(inputStream, "photo 1.jpg", context);
        }

        assertThat(stored.relativePath()).isEqualTo(
                "repair-estimates/rental-items/MSK-1/AB_12_34/estimate-draft/2026/06/28/"
                        + eventId + "/"
                        + fileId + "-photo_1.jpg"
        );
        assertThat(tempDir.resolve(stored.relativePath())).exists();
        assertThat(service.read(stored.relativePath())).isEqualTo("hello".getBytes());
    }

    @Test
    void storesIncomingPhotoIntoDeterministicFamilyRoot() throws Exception {
        RepairMediaStorageProperties properties = new RepairMediaStorageProperties();
        properties.setLocalRoot(tempDir.toString());
        properties.setObjectPrefix("repair-estimates");
        properties.setMinioEnabled(false);
        LocalMediaStorageService service = new LocalMediaStorageService(properties);

        UUID eventId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID photoId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        LocalMediaStorageService.StoragePathContext context = new LocalMediaStorageService.StoragePathContext(
                "MSK-1",
                null,
                "AB 12/34",
                "ESTIMATE_DRAFT",
                OffsetDateTime.parse("2026-06-28T10:15:30+03:00"),
                eventId,
                null
        );

        LocalMediaStorageService.StoredMedia stored;
        try (InputStream inputStream = new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8))) {
            stored = service.storeIncoming(inputStream, "photo 1.jpg", "image/jpeg", context, photoId);
        }

        assertThat(stored.familyRootKey()).isEqualTo(
                "repair-estimates/rental-items/MSK-1/AB_12_34/estimate-draft/2026/06/28/"
                        + eventId + "/"
                        + photoId
        );
        assertThat(stored.relativePath()).isEqualTo(stored.familyRootKey() + "/incoming-upload.bin");
        assertThat(tempDir.resolve(stored.relativePath())).exists();
        assertThat(service.read(stored.relativePath())).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
        assertThat(service.variantObjectKey(stored.familyRootKey(), RepairMediaVariant.PREVIEW, 1280))
                .isEqualTo(stored.familyRootKey() + "/preview_1280.webp");

        Files.writeString(tempDir.resolve(service.variantObjectKey(stored.familyRootKey(), RepairMediaVariant.ORIGINAL, null)),
                "original",
                StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve(service.variantObjectKey(stored.familyRootKey(), RepairMediaVariant.THUMB, 320)),
                "thumb",
                StandardCharsets.UTF_8);

        service.deleteFamily(stored.familyRootKey());

        assertThat(tempDir.resolve(stored.familyRootKey())).doesNotExist();
        assertThat(service.readIfExists(service.variantObjectKey(stored.familyRootKey(), RepairMediaVariant.ORIGINAL, null))).isNull();
        assertThat(service.readIfExists(service.variantObjectKey(stored.familyRootKey(), RepairMediaVariant.THUMB, 320))).isNull();
    }

    @Test
    void deleteIncomingRemovesOnlyTemporaryUpload() throws Exception {
        RepairMediaStorageProperties properties = new RepairMediaStorageProperties();
        properties.setLocalRoot(tempDir.toString());
        properties.setObjectPrefix("repair-estimates");
        properties.setMinioEnabled(false);
        LocalMediaStorageService service = new LocalMediaStorageService(properties);

        UUID eventId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID photoId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        LocalMediaStorageService.StoragePathContext context = new LocalMediaStorageService.StoragePathContext(
                "MSK-1",
                null,
                "AB 12/34",
                "ESTIMATE_DRAFT",
                OffsetDateTime.parse("2026-06-28T10:15:30+03:00"),
                eventId,
                null
        );

        LocalMediaStorageService.StoredMedia stored;
        try (InputStream inputStream = new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8))) {
            stored = service.storeIncoming(inputStream, "photo 1.jpg", "image/jpeg", context, photoId);
        }

        Files.writeString(tempDir.resolve(service.variantObjectKey(stored.familyRootKey(), RepairMediaVariant.ORIGINAL, null)),
                "original",
                StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve(service.variantObjectKey(stored.familyRootKey(), RepairMediaVariant.PREVIEW, 1280)),
                "preview",
                StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve(service.variantObjectKey(stored.familyRootKey(), RepairMediaVariant.THUMB, 320)),
                "thumb",
                StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve(service.variantObjectKey(stored.familyRootKey(), RepairMediaVariant.TINY, 96)),
                "tiny",
                StandardCharsets.UTF_8);

        service.deleteIncoming(stored.familyRootKey());

        assertThat(tempDir.resolve(stored.relativePath())).doesNotExist();
        assertThat(tempDir.resolve(service.variantObjectKey(stored.familyRootKey(), RepairMediaVariant.ORIGINAL, null))).exists();
        assertThat(tempDir.resolve(service.variantObjectKey(stored.familyRootKey(), RepairMediaVariant.PREVIEW, 1280))).exists();
        assertThat(tempDir.resolve(service.variantObjectKey(stored.familyRootKey(), RepairMediaVariant.THUMB, 320))).exists();
        assertThat(tempDir.resolve(service.variantObjectKey(stored.familyRootKey(), RepairMediaVariant.TINY, 96))).exists();
    }
}
