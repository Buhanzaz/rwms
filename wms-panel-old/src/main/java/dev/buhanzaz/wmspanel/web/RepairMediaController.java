package dev.buhanzaz.wmspanel.web;

import dev.buhanzaz.wmspanel.service.RepairEstimateService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

@RestController
public class RepairMediaController {

    private final RepairEstimateService repairEstimateService;

    public RepairMediaController(RepairEstimateService repairEstimateService) {
        this.repairEstimateService = repairEstimateService;
    }

    @GetMapping("/api/repair-media/{photoId}")
    public ResponseEntity<byte[]> media(@PathVariable UUID photoId,
                                        @RequestParam(name = "variant", defaultValue = "preview") String variant) {
        RepairEstimateService.StoredPhotoContent content = repairEstimateService.loadPhotoContent(
                photoId,
                parseVariant(variant)
        );
        MediaType mediaType = content.contentType() == null || content.contentType().isBlank()
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(content.contentType());
        return ResponseEntity.ok()
                .contentType(mediaType)
                .body(content.bytes());
    }

    @GetMapping("/api/rental-items/{rentalItemId}/latest-photos.zip")
    public ResponseEntity<byte[]> latestPhotosZip(@PathVariable UUID rentalItemId) {
        RepairEstimateService.StoredPhotoArchive archive = repairEstimateService.loadLatestPhotoArchive(rentalItemId);
        if (archive == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(archive.fileName(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .body(archive.bytes());
    }

    private RepairEstimateService.PhotoVariant parseVariant(String variant) {
        if (variant == null || variant.isBlank()) {
            return RepairEstimateService.PhotoVariant.PREVIEW;
        }
        return switch (variant.trim().toLowerCase(Locale.ROOT)) {
            case "thumb" -> RepairEstimateService.PhotoVariant.THUMB;
            case "tiny" -> RepairEstimateService.PhotoVariant.TINY;
            case "original" -> RepairEstimateService.PhotoVariant.ORIGINAL;
            default -> RepairEstimateService.PhotoVariant.PREVIEW;
        };
    }
}
