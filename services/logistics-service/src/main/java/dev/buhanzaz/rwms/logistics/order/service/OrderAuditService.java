package dev.buhanzaz.rwms.logistics.order.service;

import dev.buhanzaz.rwms.logistics.order.domain.OrderAuditEvent;
import dev.buhanzaz.rwms.logistics.order.domain.OrderAuditEventType;
import dev.buhanzaz.rwms.logistics.order.repository.OrderAuditEventRepository;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Appends logistics-local audit facts for rental-order transitions. */
@Service
@RequiredArgsConstructor
public class OrderAuditService {
  private final OrderAuditEventRepository events;

  @Transactional(propagation = Propagation.MANDATORY)
  public void append(
      UUID orderId,
      OrderAuditEventType eventType,
      OrderActor actor,
      String subjectType,
      String subjectId,
      Map<String, Object> previousValues,
      Map<String, Object> newValues) {
    appendForActor(
        orderId,
        eventType,
        actor.subjectId(),
        actor.role(),
        subjectType,
        subjectId,
        previousValues,
        newValues);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void appendForActor(
      UUID orderId,
      OrderAuditEventType eventType,
      UUID actorSubjectId,
      String actorRole,
      String subjectType,
      String subjectId,
      Map<String, Object> previousValues,
      Map<String, Object> newValues) {
    events.save(
        OrderAuditEvent.create(
            orderId,
            eventType,
            actorSubjectId,
            actorRole,
            subjectType,
            subjectId,
            previousValues,
            newValues));
  }
}
