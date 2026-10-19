package dev.buhanzaz.rwms.taskboard.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the task-board Firebase client from Application Default Credentials when FCM is enabled.
 *
 * <p>No service-account key is read from repository configuration. Production supplies ADC and a
 * project identifier through the runtime environment; a missing credential fails startup instead of
 * pretending that notifications were delivered.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "rwms.task-board.push.fcm",
    name = "enabled",
    havingValue = "true")
public class FirebaseWorkerPushConfiguration {

  /** Creates an isolated Firebase application owned by task-board-service. */
  @Bean(destroyMethod = "delete")
  FirebaseApp taskBoardFirebaseApp(
      @Value("${rwms.task-board.push.fcm.project-id}") String projectId) throws IOException {
    if (projectId == null || projectId.isBlank()) {
      throw new IllegalStateException("TASK_BOARD_FCM_PROJECT_ID обязателен при включённом FCM");
    }
    FirebaseOptions options =
        FirebaseOptions.builder()
            .setCredentials(GoogleCredentials.getApplicationDefault())
            .setProjectId(projectId.trim())
            .build();
    return FirebaseApp.initializeApp(options, "task-board-worker-push");
  }

  /** Returns the messaging client bound to the isolated task-board Firebase application. */
  @Bean
  FirebaseMessaging taskBoardFirebaseMessaging(FirebaseApp taskBoardFirebaseApp) {
    return FirebaseMessaging.getInstance(taskBoardFirebaseApp);
  }
}
