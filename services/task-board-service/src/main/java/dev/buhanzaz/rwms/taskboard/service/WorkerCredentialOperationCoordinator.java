package dev.buhanzaz.rwms.taskboard.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

/**
 * Coordinates prepare/remote/finalize worker credential effects without holding a database
 * transaction across the auth-service call.
 */
@Component
public class WorkerCredentialOperationCoordinator {
  private static final Logger LOGGER =
      LoggerFactory.getLogger(WorkerCredentialOperationCoordinator.class);
  private static final Executor DIRECT_EXECUTOR = Runnable::run;

  private final DriverManagerDataSource lockDataSource;
  private final Semaphore connectionSlots;

  public WorkerCredentialOperationCoordinator(
      Environment environment,
      @Value("${rwms.task-board.credential-lock-max-connections:4}") int maxConnections) {
    if (maxConnections < 1) {
      throw new IllegalArgumentException("Credential lock connection limit must be positive");
    }
    lockDataSource =
        new DriverManagerDataSource(
            environment.getRequiredProperty("spring.datasource.url"),
            environment.getRequiredProperty("spring.datasource.username"),
            environment.getRequiredProperty("spring.datasource.password"));
    connectionSlots = new Semaphore(maxConnections, true);
  }

  public CredentialWorkerLock tryAcquire(UUID workerId) {
    if (!connectionSlots.tryAcquire()) {
      throw new ConflictException("Лимит параллельных операций с учетными данными исчерпан");
    }
    Connection connection = null;
    try {
      connection = lockDataSource.getConnection();
      try (var statement =
          connection.prepareStatement("select set_config('application_name', ?, false)")) {
        statement.setString(1, applicationName(workerId));
        statement.executeQuery().close();
      }
      try (var statement =
          connection.prepareStatement(
              "select pg_try_advisory_lock(hashtextextended(?, 0))")) {
        statement.setString(1, lockKey(workerId));
        try (var result = statement.executeQuery()) {
          if (!result.next() || !result.getBoolean(1)) {
            destroy(connection);
            connectionSlots.release();
            throw new ConflictException(
                "Другая операция с учетными данными рабочего еще выполняется");
          }
        }
      }
      return new CredentialWorkerLock(connection, workerId);
    } catch (SQLException exception) {
      destroy(connection);
      connectionSlots.release();
      throw new ExternalServiceException(
          "Не удалось сериализовать операцию с учетными данными рабочего", exception);
    }
  }

  private String lockKey(UUID workerId) {
    return "worker-credential:" + workerId;
  }

  public static String applicationName(UUID workerId) {
    return "rwms-credential-lock:" + workerId;
  }

  private void destroy(Connection connection) {
    if (connection == null) return;
    try {
      connection.abort(DIRECT_EXECUTOR);
    } catch (SQLException abortFailure) {
      try {
        connection.close();
      } catch (SQLException closeFailure) {
        abortFailure.addSuppressed(closeFailure);
        LOGGER.warn("Credential lock connection destruction failed", abortFailure);
      }
    }
  }

  public final class CredentialWorkerLock implements AutoCloseable {
    private final Connection connection;
    private final UUID workerId;
    private boolean closed;

    private CredentialWorkerLock(Connection connection, UUID workerId) {
      this.connection = connection;
      this.workerId = workerId;
    }

    public OffsetDateTime databaseNow() {
      try (var statement = connection.prepareStatement("select clock_timestamp()");
          var result = statement.executeQuery()) {
        if (!result.next()) throw new SQLException("PostgreSQL returned no clock value");
        return result.getObject(1, OffsetDateTime.class);
      } catch (SQLException exception) {
        throw new ExternalServiceException("Не удалось получить время операции", exception);
      }
    }

    @Override
    public void close() {
      if (closed) return;
      closed = true;
      boolean unlocked = false;
      try (var statement =
          connection.prepareStatement(
              "select pg_advisory_unlock(hashtextextended(?, 0))")) {
        statement.setString(1, lockKey(workerId));
        try (var result = statement.executeQuery()) {
          unlocked = result.next() && result.getBoolean(1);
        }
      } catch (SQLException exception) {
        LOGGER.warn("Credential advisory unlock failed for worker {}", workerId, exception);
      } finally {
        if (!unlocked) {
          destroy(connection);
        } else {
          try {
            connection.close();
          } catch (SQLException exception) {
            destroy(connection);
          }
        }
        connectionSlots.release();
      }
    }
  }
}
