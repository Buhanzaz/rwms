package dev.buhanzaz.rwms.gateway.fixture;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.data.repository.Repository;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.kafka.core.KafkaOperations;

/** Groups negative fixtures for every stateful infrastructure family forbidden at the edge. */
public final class UnsafeGatewayInfrastructure {
  private UnsafeGatewayInfrastructure() {}

  /** Unsafe gateway-owned JPA entity. */
  @Entity
  public static final class GatewayEntity {
    @Id private UUID id;
  }

  /** Unsafe gateway collaborator that opens a database data source. */
  public static final class DataSourceClient {
    private DataSource dataSource;
  }

  /** Unsafe gateway collaborator that executes JDBC statements. */
  public static final class JdbcClient {
    private JdbcOperations jdbc;
  }

  /** Unsafe gateway repository backed by Spring Data. */
  public interface SpringDataGatewayRepository extends Repository<GatewayEntity, UUID> {}

  /** Unsafe gateway collaborator that participates in Kafka. */
  public static final class KafkaClient {
    private KafkaOperations<String, String> kafka;
  }

  /** Unsafe gateway-local abstraction whose repository role would introduce state ownership. */
  public interface CustomGatewayRepository {}
}
