package dev.buhanzaz.rwms.logistics.customer.service;

import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerNotificationResponse;
import dev.buhanzaz.rwms.logistics.customer.mapper.CustomerNotificationResponseMapper;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerNotificationRepository;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Subject-owned durable inbox; acknowledgement is an idempotent monotone customer action. */
@Service
@RequiredArgsConstructor
public class CustomerNotificationService {
  private static final int UNREAD_BATCH_SIZE = 50;

  private final CustomerNotificationRepository notifications;
  private final CustomerNotificationResponseMapper mapper;

  /** Returns the oldest unread entries first; acknowledged entries reveal the next bounded batch. */
  @Transactional(readOnly = true)
  public List<CustomerNotificationResponse> unread(CustomerIdentity identity) {
    return notifications
        .findByCustomerSubjectIdAndReadAtIsNullOrderByCreatedAtAscIdAsc(
            identity.subjectId(), PageRequest.of(0, UNREAD_BATCH_SIZE))
        .stream()
        .map(mapper::toResponse)
        .toList();
  }

  /** Locks an exact owned entry before recording the first acknowledgement with database time. */
  @Transactional
  public CustomerNotificationResponse markRead(CustomerIdentity identity, UUID notificationId) {
    var notification =
        notifications
            .findOwnedForUpdate(notificationId, identity.subjectId())
            .orElseThrow(CustomerNotificationService::notFound);
    if (notification.getReadAt() == null) {
      notification.markRead(notifications.currentDatabaseTimestamp().atOffset(ZoneOffset.UTC));
    }
    return mapper.toResponse(notification);
  }

  private static OrderProblemException notFound() {
    return new OrderProblemException(
        HttpStatus.NOT_FOUND, "CUSTOMER_NOTIFICATION_NOT_FOUND", "Уведомление не найдено");
  }
}
