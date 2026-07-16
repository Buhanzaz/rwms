package dev.buhanzaz.wmspanel.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.buhanzaz.wmspanel.entity.PhotoProcessingStatus;
import dev.buhanzaz.wmspanel.entity.RentalItemEventPhoto;
import io.jmix.core.DataManager;
import io.jmix.core.security.SystemAuthenticator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class PhotoProcessingQueueService {

    private static final Logger log = LoggerFactory.getLogger(PhotoProcessingQueueService.class);

    private final RepairMediaStorageProperties properties;
    private final ObjectMapper objectMapper;
    private final RabbitTemplate rabbitTemplate;
    private final DataManager dataManager;
    private final SystemAuthenticator systemAuthenticator;
    private final LocalMediaStorageService localMediaStorageService;

    public PhotoProcessingQueueService(RepairMediaStorageProperties properties,
                                       ObjectMapper objectMapper,
                                       RabbitTemplate rabbitTemplate,
                                       DataManager dataManager,
                                       SystemAuthenticator systemAuthenticator,
                                       LocalMediaStorageService localMediaStorageService) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.dataManager = dataManager;
        this.systemAuthenticator = systemAuthenticator;
        this.localMediaStorageService = localMediaStorageService;
    }

    public boolean queueEnabled() {
        return properties.isQueueEnabled();
    }

    public void publish(PhotoProcessingTaskMessage message) {
        if (!queueEnabled() || message == null) {
            return;
        }
        try {
            rabbitTemplate.convertAndSend(properties.getProcessingQueue(), objectMapper.writeValueAsString(message));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Не удалось сериализовать задачу обработки фото", ex);
        }
    }

    @Transactional
    @RabbitListener(queues = "#{@repairMediaStorageProperties.processingResultQueue}", autoStartup = "#{@repairMediaStorageProperties.queueEnabled}")
    public void handleResult(String payload) {
        systemAuthenticator.begin("admin");
        try {
            PhotoProcessingResultMessage result;
            try {
                result = objectMapper.readValue(payload, PhotoProcessingResultMessage.class);
            } catch (Exception ex) {
                log.warn("Не удалось разобрать результат обработки фото: {}", payload, ex);
                return;
            }
            if (result.photoId() == null) {
                return;
            }
            RentalItemEventPhoto photo = dataManager.load(RentalItemEventPhoto.class)
                    .id(result.photoId())
                    .optional()
                    .orElse(null);
            if (photo == null) {
                log.warn("Результат обработки фото пришёл для отсутствующего photoId={}", result.photoId());
                return;
            }
            PhotoProcessingStatus status = PhotoProcessingStatus.fromId(result.status());
            photo.setProcessingStatus(status == null ? PhotoProcessingStatus.FAILED : status);
            if (result.originalObjectKey() != null && !result.originalObjectKey().isBlank()) {
                photo.setStoragePath(result.originalObjectKey());
            }
            photo.setOriginalWidth(result.originalWidth());
            photo.setOriginalHeight(result.originalHeight());
            photo.setPreviewWidth(result.previewWidth());
            photo.setPreviewHeight(result.previewHeight());
            photo.setThumbWidth(result.thumbWidth());
            photo.setThumbHeight(result.thumbHeight());
            photo.setTinyWidth(result.tinyWidth());
            photo.setTinyHeight(result.tinyHeight());
            photo.setProcessingError(result.error());
            if (photo.getProcessingStatus() == PhotoProcessingStatus.READY) {
                photo.setContentType("image/jpeg");
            }
            dataManager.save(photo);
            if (photo.getProcessingStatus() == PhotoProcessingStatus.READY) {
                localMediaStorageService.deleteIncoming(resolveFamilyRootKey(photo, result));
            }
        } finally {
            systemAuthenticator.end();
        }
    }

    public void markFailed(UUID photoId, String error) {
        if (photoId == null) {
            return;
        }
        RentalItemEventPhoto photo = dataManager.load(RentalItemEventPhoto.class)
                .id(photoId)
                .optional()
                .orElse(null);
        if (photo == null) {
            return;
        }
        photo.setProcessingStatus(PhotoProcessingStatus.FAILED);
        photo.setProcessingError(error);
        dataManager.save(photo);
    }

    private String resolveFamilyRootKey(RentalItemEventPhoto photo, PhotoProcessingResultMessage result) {
        String familyRootKey = photo.getFamilyRootKey();
        if (familyRootKey != null && !familyRootKey.isBlank()) {
            return familyRootKey;
        }
        String originalObjectKey = result.originalObjectKey();
        if (originalObjectKey == null || originalObjectKey.isBlank()) {
            return null;
        }
        int lastSlash = originalObjectKey.lastIndexOf('/');
        if (lastSlash < 0) {
            return null;
        }
        return originalObjectKey.substring(0, lastSlash);
    }

    @Configuration
    @EnableRabbit
    @ConditionalOnProperty(prefix = "repair.media", name = "queue-enabled", havingValue = "true")
    static class RabbitConfig {
        @Bean
        Queue repairMediaProcessingQueue(RepairMediaStorageProperties properties) {
            return new Queue(properties.getProcessingQueue(), true);
        }

        @Bean
        Queue repairMediaProcessingResultQueue(RepairMediaStorageProperties properties) {
            return new Queue(properties.getProcessingResultQueue(), true);
        }
    }
}
