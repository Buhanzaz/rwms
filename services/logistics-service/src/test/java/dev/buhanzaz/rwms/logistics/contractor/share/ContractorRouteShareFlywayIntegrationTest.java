package dev.buhanzaz.rwms.logistics.contractor.share;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Verifies the immutable V81 upgrade and its database-level identity/lifecycle fences. */
@Testcontainers
class ContractorRouteShareFlywayIntegrationTest {
  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  private JdbcTemplate jdbc;

  @BeforeEach
  void resetDatabase() {
    jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    jdbc.execute("drop schema public cascade");
    jdbc.execute("create schema public");
  }

  @Test
  void v80UpgradeCreatesOnlyLocalShareLifecycleAndOrderedMembership() {
    Flyway before = flyway("80");
    before.migrate();
    assertThat(before.info().current().getVersion().getVersion()).isEqualTo("80");

    Flyway current = flyway("81");
    assertThat(current.migrate().migrationsExecuted).isOne();
    current.validate();
    assertThat(current.info().current().getVersion().getVersion()).isEqualTo("81");

    assertThat(table("contractor_route_share")).isEqualTo("contractor_route_share");
    assertThat(table("contractor_route_share_task")).isEqualTo("contractor_route_share_task");
    assertThat(index("uk_contractor_route_share_subject_key")).isNotNull();
    assertThat(index("idx_contractor_route_share_warehouse_expiry")).isNotNull();
    assertThat(index("idx_contractor_route_share_contractor_expiry")).isNotNull();
    assertThat(index("idx_contractor_route_share_task_external")).isNotNull();
    assertThat(constraint("contractor_route_share", "ck_contractor_route_share_token_revision"))
        .contains("token_revision >= 1");
    assertThat(constraint("contractor_route_share", "ck_contractor_route_share_expiry"))
        .contains("expires_at > created_at");
    assertThat(constraint("contractor_route_share_task", "fk_contractor_route_share_task_share"))
        .contains("ON DELETE CASCADE");
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                from information_schema.columns
                where table_schema = 'public'
                  and table_name in ('contractor_route_share', 'contractor_route_share_task')
                  and column_name in ('address', 'contact_phone', 'cargo_json', 'media_url', 'task_snapshot_json')
                """,
                Integer.class))
        .isZero();
  }

  private Flyway flyway(String target) {
    var configuration =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .baselineOnMigrate(false)
            .validateMigrationNaming(true);
    if (target != null) configuration.target(target);
    return configuration.load();
  }

  private String table(String name) {
    return jdbc.queryForObject("select to_regclass('public.' || ?)", String.class, name);
  }

  private String index(String name) {
    return jdbc.queryForObject("select to_regclass('public.' || ?)", String.class, name);
  }

  private String constraint(String table, String name) {
    return jdbc.queryForObject(
        """
        select pg_get_constraintdef(c.oid)
        from pg_constraint c
        join pg_class t on t.oid = c.conrelid
        join pg_namespace n on n.oid = t.relnamespace
        where n.nspname = 'public' and t.relname = ? and c.conname = ?
        """,
        String.class,
        table,
        name);
  }
}
