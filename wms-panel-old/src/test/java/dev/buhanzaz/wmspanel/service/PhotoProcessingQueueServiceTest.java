package dev.buhanzaz.wmspanel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.buhanzaz.wmspanel.entity.PhotoProcessingStatus;
import dev.buhanzaz.wmspanel.entity.RentalItemEventPhoto;
import io.jmix.core.DataManager;
import io.jmix.core.security.SystemAuthenticator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PhotoProcessingQueueServiceTest {

    private final RepairMediaStorageProperties properties = new RepairMediaStorageProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final DataManager dataManager = mock(DataManager.class, Answers.RETURNS_DEEP_STUBS);
    private final SystemAuthenticator systemAuthenticator = mock(SystemAuthenticator.class);
    private final LocalMediaStorageService localMediaStorageService = mock(LocalMediaStorageService.class);

    private PhotoProcessingQueueService service;

    @BeforeEach
    void setUp() {
        service = new PhotoProcessingQueueService(
                properties,
                objectMapper,
                rabbitTemplate,
                dataManager,
                systemAuthenticator,
                localMediaStorageService
        );
    }

    @Test
    void readyResultDeletesOnlyIncomingObject() throws Exception {
        UUID photoId = UUID.randomUUID();
        RentalItemEventPhoto photo = new RentalItemEventPhoto();
        photo.setId(photoId);
        photo.setFamilyRootKey("repair-estimates/rental-items/MSK-1/AB_12_34/estimate-draft/2026/06/28/event/" + photoId);

        when(dataManager.load(RentalItemEventPhoto.class).id(photoId).optional()).thenReturn(Optional.of(photo));
        when(dataManager.save(photo)).thenReturn(photo);

        PhotoProcessingResultMessage result = new PhotoProcessingResultMessage(
                photoId,
                "READY",
                photo.getFamilyRootKey() + "/original.jpg",
                1024,
                768,
                800,
                600,
                320,
                240,
                96,
                72,
                null
        );

        service.handleResult(objectMapper.writeValueAsString(result));

        assertThat(photo.getProcessingStatus()).isEqualTo(PhotoProcessingStatus.READY);
        assertThat(photo.getContentType()).isEqualTo("image/jpeg");
        verify(localMediaStorageService).deleteIncoming(photo.getFamilyRootKey());
        verifyNoInteractions(rabbitTemplate);
        verify(systemAuthenticator).begin("admin");
        verify(systemAuthenticator).end();
    }

    @Test
    void failedResultKeepsIncomingObjectForRetry() throws Exception {
        UUID photoId = UUID.randomUUID();
        RentalItemEventPhoto photo = new RentalItemEventPhoto();
        photo.setId(photoId);
        photo.setFamilyRootKey("repair-estimates/rental-items/MSK-1/AB_12_34/estimate-draft/2026/06/28/event/" + photoId);

        when(dataManager.load(RentalItemEventPhoto.class).id(photoId).optional()).thenReturn(Optional.of(photo));
        when(dataManager.save(photo)).thenReturn(photo);

        PhotoProcessingResultMessage result = new PhotoProcessingResultMessage(
                photoId,
                "FAILED",
                photo.getFamilyRootKey() + "/original.jpg",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "boom"
        );

        service.handleResult(objectMapper.writeValueAsString(result));

        assertThat(photo.getProcessingStatus()).isEqualTo(PhotoProcessingStatus.FAILED);
        assertThat(photo.getProcessingError()).isEqualTo("boom");
        verifyNoInteractions(localMediaStorageService);
    }
}
