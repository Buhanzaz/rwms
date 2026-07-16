package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;

@JmixEntity
@Entity
@Table(name = "RENTAL_ITEM_EVENT_PHOTO")
public class RentalItemEventPhoto extends UuidEntity {

    @NotNull
    @JoinColumn(name = "EVENT_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private RentalItemEvent event;

    @Column(name = "STORAGE_PATH", nullable = false, length = 1000)
    private String storagePath;

    @Column(name = "ORIGINAL_FILE_NAME", length = 255)
    private String originalFileName;

    @Column(name = "CONTENT_TYPE", length = 128)
    private String contentType;

    @Column(name = "SIZE_BYTES")
    private Long sizeBytes;

    @Column(name = "FAMILY_ROOT_KEY", length = 1000)
    private String familyRootKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "PROCESSING_STATUS", length = 32)
    private PhotoProcessingStatus processingStatus;

    @Column(name = "ORIGINAL_WIDTH")
    private Integer originalWidth;

    @Column(name = "ORIGINAL_HEIGHT")
    private Integer originalHeight;

    @Column(name = "PREVIEW_WIDTH")
    private Integer previewWidth;

    @Column(name = "PREVIEW_HEIGHT")
    private Integer previewHeight;

    @Column(name = "THUMB_WIDTH")
    private Integer thumbWidth;

    @Column(name = "THUMB_HEIGHT")
    private Integer thumbHeight;

    @Column(name = "TINY_WIDTH")
    private Integer tinyWidth;

    @Column(name = "TINY_HEIGHT")
    private Integer tinyHeight;

    @Column(name = "PROCESSING_ERROR", length = 2000)
    private String processingError;

    @Column(name = "SORT_ORDER", nullable = false)
    private Integer sortOrder = 0;

    public RentalItemEvent getEvent() {
        return event;
    }

    public void setEvent(RentalItemEvent event) {
        this.event = event;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public void setOriginalFileName(String originalFileName) {
        this.originalFileName = originalFileName;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public String getFamilyRootKey() {
        return familyRootKey;
    }

    public void setFamilyRootKey(String familyRootKey) {
        this.familyRootKey = familyRootKey;
    }

    public PhotoProcessingStatus getProcessingStatus() {
        return processingStatus;
    }

    public void setProcessingStatus(PhotoProcessingStatus processingStatus) {
        this.processingStatus = processingStatus;
    }

    public Integer getOriginalWidth() {
        return originalWidth;
    }

    public void setOriginalWidth(Integer originalWidth) {
        this.originalWidth = originalWidth;
    }

    public Integer getOriginalHeight() {
        return originalHeight;
    }

    public void setOriginalHeight(Integer originalHeight) {
        this.originalHeight = originalHeight;
    }

    public Integer getPreviewWidth() {
        return previewWidth;
    }

    public void setPreviewWidth(Integer previewWidth) {
        this.previewWidth = previewWidth;
    }

    public Integer getPreviewHeight() {
        return previewHeight;
    }

    public void setPreviewHeight(Integer previewHeight) {
        this.previewHeight = previewHeight;
    }

    public Integer getThumbWidth() {
        return thumbWidth;
    }

    public void setThumbWidth(Integer thumbWidth) {
        this.thumbWidth = thumbWidth;
    }

    public Integer getThumbHeight() {
        return thumbHeight;
    }

    public void setThumbHeight(Integer thumbHeight) {
        this.thumbHeight = thumbHeight;
    }

    public Integer getTinyWidth() {
        return tinyWidth;
    }

    public void setTinyWidth(Integer tinyWidth) {
        this.tinyWidth = tinyWidth;
    }

    public Integer getTinyHeight() {
        return tinyHeight;
    }

    public void setTinyHeight(Integer tinyHeight) {
        this.tinyHeight = tinyHeight;
    }

    public String getProcessingError() {
        return processingError;
    }

    public void setProcessingError(String processingError) {
        this.processingError = processingError;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }
}
