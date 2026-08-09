package dev.buhanzaz.rwms.maintenance.disposition.application;

import dev.buhanzaz.rwms.maintenance.service.MaintenanceChecksum;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Technical local-command boundary for property disposition work.
 *
 * <p>It intentionally owns only transaction execution, PostgreSQL JSONB canonicalization, and
 * transaction-scoped advisory locks. Business state, repair rules, and disposition decisions stay
 * with their dedicated collaborators.
 */
@Service
final class PropertyDispositionCommandBoundary {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final TransactionTemplate transactions;

  PropertyDispositionCommandBoundary(
      JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  <T> T execute(Supplier<T> action) {
    return transactions.execute(ignored -> action.get());
  }

  <T> T requiredResult(T value) {
    if (value == null) {
      throw new IllegalStateException("Property disposition transaction returned no result");
    }
    return value;
  }

  String hash(Object value) {
    try {
      String serialized = mapper.writeValueAsString(value);
      String canonical = jdbc.queryForObject("select (?::jsonb)::text", String.class, serialized);
      if (canonical == null) {
        throw new IllegalStateException("PostgreSQL did not canonicalize property disposition request");
      }
      return MaintenanceChecksum.sha256(canonical.getBytes(StandardCharsets.UTF_8));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Property disposition request cannot be serialized", exception);
    }
  }

  void advisoryLock(String key) {
    jdbc.query(
        "select pg_advisory_xact_lock(hashtextextended(?, 0))",
        resultSet -> {},
        key);
  }
}
