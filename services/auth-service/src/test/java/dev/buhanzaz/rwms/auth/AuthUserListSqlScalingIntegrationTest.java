package dev.buhanzaz.rwms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.auth.api.ActorDisplayResponse;
import dev.buhanzaz.rwms.auth.api.AdminUserResponse;
import dev.buhanzaz.rwms.auth.api.CreateUserRequest;
import dev.buhanzaz.rwms.auth.api.WarehouseAccessRequest;
import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import dev.buhanzaz.rwms.auth.domain.WarehouseAccessLevel;
import dev.buhanzaz.rwms.auth.eventing.AuthSubjectProfileStore;
import dev.buhanzaz.rwms.auth.eventing.UserWarehouseAccessNoteStore;
import dev.buhanzaz.rwms.auth.integration.warehouse.WarehouseExistenceClient;
import dev.buhanzaz.rwms.auth.service.UserAdministrationService;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies that admin identity reads use a fixed number of JDBC statements as result sizes grow.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(AuthUserListSqlScalingIntegrationTest.SqlCountingConfiguration.class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthUserListSqlScalingIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired UserAdministrationService users;
  @Autowired SqlStatementCollector sqlStatements;
  @Autowired AuthSubjectProfileStore profiles;
  @Autowired UserWarehouseAccessNoteStore accessNotes;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean WarehouseExistenceClient warehouseExistenceClient;

  @Test
  void listAndActorDisplaysPreserveResponsesWithoutGrowingJdbcStatementCounts() {
    List<AdminUserResponse> created = createUsers(0, 2);
    Counted<List<AdminUserResponse>> listTwo =
        sqlStatements.measure(() -> users.listUsers(adminAuthentication()));
    created.addAll(createUsers(2, 3));
    Counted<List<AdminUserResponse>> listFive =
        sqlStatements.measure(() -> users.listUsers(adminAuthentication()));

    assertThat(listTwo.preparedStatements()).isPositive();
    assertThat(listFive.preparedStatements()).isEqualTo(listTwo.preparedStatements());
    assertThat(listFive.value())
        .filteredOn(user -> user.username().startsWith("sql-scaling-"))
        .extracting(AdminUserResponse::username)
        .containsExactly(
            "sql-scaling-00",
            "sql-scaling-01",
            "sql-scaling-02",
            "sql-scaling-03",
            "sql-scaling-04");
    assertThat(listFive.value())
        .filteredOn(user -> user.username().equals("sql-scaling-02"))
        .singleElement()
        .satisfies(
            user -> {
              assertThat(user.globalRole()).isEqualTo(UserGlobalRole.WMS_ADMIN);
              assertThat(user.warehouseAccesses())
                  .extracting(access -> access.warehouseId())
                  .containsExactly(warehouseId(2, 1), warehouseId(2, 2));
              assertThat(user.warehouseAccesses())
                  .extracting(access -> access.comment())
                  .containsExactly("note-02-a", "note-02-b");
            });

    Counted<List<ActorDisplayResponse>> actorsTwo =
        sqlStatements.measure(
            () ->
                users.actorDisplays(
                    created.subList(0, 2).stream().map(AdminUserResponse::id).toList(),
                    adminAuthentication()));
    UUID missing = UUID.randomUUID();
    Counted<List<ActorDisplayResponse>> actorsFive =
        sqlStatements.measure(
            () ->
                users.actorDisplays(
                    List.of(
                        created.get(4).id(),
                        missing,
                        created.get(1).id(),
                        created.get(4).id(),
                        created.get(0).id(),
                        created.get(2).id(),
                        created.get(3).id()),
                    adminAuthentication()));

    assertThat(actorsTwo.preparedStatements()).isPositive();
    assertThat(actorsFive.preparedStatements()).isEqualTo(actorsTwo.preparedStatements());
    assertThat(actorsFive.value())
        .extracting(display -> display.subjectId())
        .containsExactly(
            created.get(4).id(),
            created.get(1).id(),
            created.get(0).id(),
            created.get(2).id(),
            created.get(3).id());
    assertThat(actorsFive.value())
        .filteredOn(display -> display.subjectId().equals(created.get(2).id()))
        .singleElement()
        .satisfies(
            display -> {
              assertThat(display.globalRole()).isEqualTo(UserGlobalRole.WMS_ADMIN);
              assertThat(display.username()).isEqualTo("sql-scaling-02");
            });
  }

  @Test
  void vaultBatchReadsAcceptMoreThanJdbcBindLimitAsOneUuidArray() {
    AdminUserResponse created = createUsers("sql-array-probe-", 0, 1).getFirst();
    UUID accessId =
        jdbc.queryForObject(
            "select id from user_warehouse_access where user_id = ? order by warehouse_id limit 1",
            UUID.class,
            created.id());
    List<UUID> requested =
        new ArrayList<>(IntStream.range(1, 70_001).mapToObj(index -> new UUID(0, index)).toList());
    requested.add(created.id());
    requested.add(accessId);

    assertThat(profiles.findAllBySubjectIds(requested)).containsKey(created.id());
    assertThat(accessNotes.findAllByAccessIds(requested)).containsKey(accessId);
  }

  private List<AdminUserResponse> createUsers(int firstIndex, int count) {
    return createUsers("sql-scaling-", firstIndex, count);
  }

  private List<AdminUserResponse> createUsers(String usernamePrefix, int firstIndex, int count) {
    List<AdminUserResponse> created = new ArrayList<>();
    for (int index = firstIndex; index < firstIndex + count; index++) {
      String suffix = "%02d".formatted(index);
      created.add(
          users.create(
              new CreateUserRequest(
                  usernamePrefix + suffix,
                  "sql-scaling-password",
                  "First " + suffix,
                  "Last " + suffix,
                  "sql-scaling-" + suffix + "@example.test",
                  "Europe/Moscow",
                  UserGlobalRole.WMS_ADMIN,
                  true,
                  List.of(
                      new WarehouseAccessRequest(
                          warehouseId(index, 2),
                          WarehouseAccessLevel.MANAGE,
                          "note-" + suffix + "-b",
                          true),
                      new WarehouseAccessRequest(
                          warehouseId(index, 1),
                          WarehouseAccessLevel.VIEW,
                          "note-" + suffix + "-a",
                          true))),
              adminAuthentication()));
    }
    return created;
  }

  private String warehouseId(int userIndex, int warehouseIndex) {
    return "00000000-0000-0000-0000-%012d".formatted(userIndex * 10L + warehouseIndex);
  }

  private UsernamePasswordAuthenticationToken adminAuthentication() {
    return UsernamePasswordAuthenticationToken.authenticated("admin", "", List.of());
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class SqlCountingConfiguration {
    @Bean
    SqlStatementCollector sqlStatementCollector() {
      return new SqlStatementCollector();
    }

    @Bean
    static BeanPostProcessor sqlCountingDataSourcePostProcessor(SqlStatementCollector collector) {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
          return "dataSource".equals(beanName) && bean instanceof DataSource source
              ? collector.wrap(source)
              : bean;
        }
      };
    }
  }

  static final class SqlStatementCollector {
    private final ThreadLocal<AtomicInteger> activeCount = new ThreadLocal<>();

    /** Counts statements only while the synchronous operation runs on the calling thread. */
    <T> Counted<T> measure(Supplier<T> operation) {
      AtomicInteger count = new AtomicInteger();
      activeCount.set(count);
      try {
        return new Counted<>(operation.get(), count.get());
      } finally {
        activeCount.remove();
      }
    }

    DataSource wrap(DataSource delegate) {
      return new CountingDataSource(delegate, this);
    }

    private Connection count(Connection connection) {
      return (Connection)
          Proxy.newProxyInstance(
              connection.getClass().getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, arguments) -> {
                if (method.getName().equals("prepareStatement") && activeCount.get() != null)
                  activeCount.get().incrementAndGet();
                try {
                  return method.invoke(connection, arguments);
                } catch (InvocationTargetException exception) {
                  throw exception.getCause();
                }
              });
    }
  }

  record Counted<T>(T value, int preparedStatements) {}

  private static final class CountingDataSource implements DataSource {
    private final DataSource delegate;
    private final SqlStatementCollector collector;

    private CountingDataSource(DataSource delegate, SqlStatementCollector collector) {
      this.delegate = delegate;
      this.collector = collector;
    }

    @Override
    public Connection getConnection() throws SQLException {
      return collector.count(delegate.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return collector.count(delegate.getConnection(username, password));
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
      return delegate.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
      delegate.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
      delegate.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
      return delegate.getLoginTimeout();
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      return delegate.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
      return delegate.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
      return delegate.isWrapperFor(iface);
    }
  }
}
