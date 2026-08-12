package dev.buhanzaz.rwms.taskboard.push;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Sends data-only worker invalidations through Firebase Cloud Messaging HTTP v1. */
@Component
@ConditionalOnProperty(
    prefix = "rwms.task-board.push.fcm",
    name = "enabled",
    havingValue = "true")
public class FirebaseWorkerPushClient implements WorkerPushClient {
  private static final Set<MessagingErrorCode> RETRYABLE =
      Set.of(
          MessagingErrorCode.INTERNAL,
          MessagingErrorCode.QUOTA_EXCEEDED,
          MessagingErrorCode.UNAVAILABLE,
          MessagingErrorCode.THIRD_PARTY_AUTH_ERROR);

  private final FirebaseMessaging messaging;

  public FirebaseWorkerPushClient(FirebaseMessaging messaging) {
    this.messaging = messaging;
  }

  @Override
  public void send(WorkerPushClient.Message message, Target target) throws DeliveryException {
    com.google.firebase.messaging.Message.Builder builder =
        com.google.firebase.messaging.Message.builder()
            .putData("eventId", message.eventId().toString())
            .putData("revision", Long.toString(message.revision()))
            .putData("type", message.type())
            .putData("entryId", message.entryId().toString());
    switch (target.targetKind()) {
      case "FID" -> builder.setFid(target.value());
      case "TOKEN" -> builder.setToken(target.value());
      default ->
          throw new DeliveryException(
              "Unsupported Firebase target kind", true, false, null);
    }
    try {
      messaging.send(builder.build());
    } catch (FirebaseMessagingException exception) {
      MessagingErrorCode code = exception.getMessagingErrorCode();
      boolean invalidTarget = code == MessagingErrorCode.UNREGISTERED;
      boolean retryable = code == null || RETRYABLE.contains(code);
      throw new DeliveryException(
          code == null ? "FCM delivery failed" : "FCM delivery failed: " + code.name(),
          invalidTarget,
          retryable,
          exception);
    }
  }
}
