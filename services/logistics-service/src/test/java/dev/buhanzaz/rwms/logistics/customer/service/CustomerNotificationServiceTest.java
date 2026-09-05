package dev.buhanzaz.rwms.logistics.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerNotification;
import dev.buhanzaz.rwms.logistics.customer.mapper.CustomerNotificationResponseMapper;
import dev.buhanzaz.rwms.logistics.customer.repository.CustomerNotificationRepository;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/** Verifies bounded, subject-owned durable inbox reads and monotone acknowledgement retries. */
class CustomerNotificationServiceTest {
  private static final UUID SUBJECT = UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID OTHER_SUBJECT =
      UUID.fromString("10000000-0000-0000-0000-000000000002");
  private static final UUID ORDER = UUID.fromString("20000000-0000-0000-0000-000000000001");
  private static final UUID BOOKING = UUID.fromString("30000000-0000-0000-0000-000000000001");
  private static final UUID NOTIFICATION =
      UUID.fromString("40000000-0000-0000-0000-000000000001");
  private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-09-05T10:00:00Z");

  private final CustomerNotificationRepository notifications =
      mock(CustomerNotificationRepository.class);
  private final CustomerNotificationResponseMapper mapper =
      Mappers.getMapper(CustomerNotificationResponseMapper.class);
  private final CustomerIdentity identity = new CustomerIdentity(SUBJECT, "customer");
  private CustomerNotificationService service;

  @BeforeEach
  void setUp() {
    service = new CustomerNotificationService(notifications, mapper);
  }

  @Test
  void returnsTheOldestUnreadBoundedBatchWithoutExposingTheSubject() {
    CustomerNotification first = notification(NOTIFICATION, CREATED_AT);
    CustomerNotification second =
        notification(
            UUID.fromString("40000000-0000-0000-0000-000000000002"), CREATED_AT.plusMinutes(1));
    when(notifications.findByCustomerSubjectIdAndReadAtIsNullOrderByCreatedAtAscIdAsc(
            eq(SUBJECT), org.mockito.ArgumentMatchers.any(Pageable.class)))
        .thenReturn(List.of(first, second));

    var response = service.unread(identity);

    ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
    verify(notifications)
        .findByCustomerSubjectIdAndReadAtIsNullOrderByCreatedAtAscIdAsc(
            eq(SUBJECT), page.capture());
    assertThat(page.getValue().getPageNumber()).isZero();
    assertThat(page.getValue().getPageSize()).isEqualTo(50);
    assertThat(response)
        .extracting(item -> item.id())
        .containsExactly(NOTIFICATION, second.getId());
    assertThat(response.getFirst().orderId()).isEqualTo(ORDER);
    assertThat(response.getFirst().bookingId()).isEqualTo(BOOKING);
    assertThat(response.getFirst().kind()).isEqualTo("PAYMENT_EXPIRED");
    assertThat(response.getFirst().createdAt()).isEqualTo(CREATED_AT);
    assertThat(response.getFirst().readAt()).isNull();
  }

  @Test
  void acknowledgesAnOwnedEntryWithDatabaseTimeAndKeepsTheFirstReadTimeOnRetry() {
    CustomerNotification notification = notification(NOTIFICATION, CREATED_AT);
    OffsetDateTime firstReadAt = CREATED_AT.plusMinutes(2);
    when(notifications.findOwnedForUpdate(NOTIFICATION, SUBJECT))
        .thenReturn(Optional.of(notification));
    when(notifications.currentDatabaseTimestamp()).thenReturn(firstReadAt.toInstant());

    var first = service.markRead(identity, NOTIFICATION);
    var retry = service.markRead(identity, NOTIFICATION);

    verify(notifications, org.mockito.Mockito.times(2)).findOwnedForUpdate(NOTIFICATION, SUBJECT);
    verify(notifications).currentDatabaseTimestamp();
    assertThat(first.readAt()).isEqualTo(firstReadAt);
    assertThat(retry.readAt()).isEqualTo(firstReadAt);
    assertThat(notification.getReadAt()).isEqualTo(firstReadAt);
  }

  @Test
  void treatsAnotherCustomersNotificationAsNotFoundWithoutReadingTheDatabaseClock() {
    when(notifications.findOwnedForUpdate(NOTIFICATION, OTHER_SUBJECT)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> service.markRead(new CustomerIdentity(OTHER_SUBJECT, "other"), NOTIFICATION))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> {
              assertThat(problem.status().value()).isEqualTo(404);
              assertThat(problem.code()).isEqualTo("CUSTOMER_NOTIFICATION_NOT_FOUND");
            });

    verify(notifications, never()).currentDatabaseTimestamp();
  }

  private static CustomerNotification notification(UUID id, OffsetDateTime createdAt) {
    CustomerNotification notification =
        CustomerNotification.paymentExpired(SUBJECT, ORDER, BOOKING, "ORD-1", createdAt);
    ReflectionTestUtils.setField(notification, "id", id);
    return notification;
  }
}
