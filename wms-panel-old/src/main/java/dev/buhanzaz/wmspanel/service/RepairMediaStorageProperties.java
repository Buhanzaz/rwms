package dev.buhanzaz.wmspanel.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "repair.media")
public class RepairMediaStorageProperties {

    private String localRoot = ".media/rental-item-events";
    private String objectPrefix = "repair-estimates";
    private String incomingObjectName = "incoming-upload.bin";
    private boolean minioEnabled = true;
    private String minioEndpoint = "http://localhost:9000";
    private String minioAccessKey = "minioadmin";
    private String minioSecretKey = "minioadmin";
    private String minioBucket = "repair-media";
    private boolean minioAutoCreateBucket = true;
    private boolean queueEnabled = true;
    private String processingQueue = "repair.media.process";
    private String processingResultQueue = "repair.media.processed";
    private Integer previewLongEdge = 1280;
    private Integer thumbLongEdge = 320;
    private Integer tinyLongEdge = 96;

    public String getLocalRoot() {
        return localRoot;
    }

    public void setLocalRoot(String localRoot) {
        this.localRoot = localRoot;
    }

    public String getObjectPrefix() {
        return objectPrefix;
    }

    public void setObjectPrefix(String objectPrefix) {
        this.objectPrefix = objectPrefix;
    }

    public String getIncomingObjectName() {
        return incomingObjectName;
    }

    public void setIncomingObjectName(String incomingObjectName) {
        this.incomingObjectName = incomingObjectName;
    }

    public boolean isMinioEnabled() {
        return minioEnabled;
    }

    public void setMinioEnabled(boolean minioEnabled) {
        this.minioEnabled = minioEnabled;
    }

    public String getMinioEndpoint() {
        return minioEndpoint;
    }

    public void setMinioEndpoint(String minioEndpoint) {
        this.minioEndpoint = minioEndpoint;
    }

    public String getMinioAccessKey() {
        return minioAccessKey;
    }

    public void setMinioAccessKey(String minioAccessKey) {
        this.minioAccessKey = minioAccessKey;
    }

    public String getMinioSecretKey() {
        return minioSecretKey;
    }

    public void setMinioSecretKey(String minioSecretKey) {
        this.minioSecretKey = minioSecretKey;
    }

    public String getMinioBucket() {
        return minioBucket;
    }

    public void setMinioBucket(String minioBucket) {
        this.minioBucket = minioBucket;
    }

    public boolean isMinioAutoCreateBucket() {
        return minioAutoCreateBucket;
    }

    public void setMinioAutoCreateBucket(boolean minioAutoCreateBucket) {
        this.minioAutoCreateBucket = minioAutoCreateBucket;
    }

    public boolean isQueueEnabled() {
        return queueEnabled;
    }

    public void setQueueEnabled(boolean queueEnabled) {
        this.queueEnabled = queueEnabled;
    }

    public String getProcessingQueue() {
        return processingQueue;
    }

    public void setProcessingQueue(String processingQueue) {
        this.processingQueue = processingQueue;
    }

    public String getProcessingResultQueue() {
        return processingResultQueue;
    }

    public void setProcessingResultQueue(String processingResultQueue) {
        this.processingResultQueue = processingResultQueue;
    }

    public Integer getPreviewLongEdge() {
        return previewLongEdge;
    }

    public void setPreviewLongEdge(Integer previewLongEdge) {
        this.previewLongEdge = previewLongEdge;
    }

    public Integer getThumbLongEdge() {
        return thumbLongEdge;
    }

    public void setThumbLongEdge(Integer thumbLongEdge) {
        this.thumbLongEdge = thumbLongEdge;
    }

    public Integer getTinyLongEdge() {
        return tinyLongEdge;
    }

    public void setTinyLongEdge(Integer tinyLongEdge) {
        this.tinyLongEdge = tinyLongEdge;
    }

    public boolean isMinioConfigured() {
        return minioEnabled
                && minioEndpoint != null && !minioEndpoint.isBlank()
                && minioAccessKey != null && !minioAccessKey.isBlank()
                && minioSecretKey != null && !minioSecretKey.isBlank()
                && minioBucket != null && !minioBucket.isBlank();
    }
}
