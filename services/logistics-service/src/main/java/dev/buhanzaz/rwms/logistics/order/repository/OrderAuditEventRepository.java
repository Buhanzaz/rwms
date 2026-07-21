package dev.buhanzaz.rwms.logistics.order.repository;

import dev.buhanzaz.rwms.logistics.order.domain.OrderAuditEvent;
import dev.buhanzaz.rwms.logistics.order.domain.OrderAuditEventType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderAuditEventRepository extends JpaRepository<OrderAuditEvent, UUID> {
  List<OrderAuditEvent> findAllByOrderIdOrderByOccurredAtAscIdAsc(UUID orderId);

  boolean existsByOrderIdAndEventTypeAndSubjectTypeAndSubjectId(
      UUID orderId,
      OrderAuditEventType eventType,
      String subjectType,
      String subjectId);
}
