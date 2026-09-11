package dev.buhanzaz.rwms.taskboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Verifies the additive workforce archive upgrade against an existing PostgreSQL directory. */
@Testcontainers
class WorkforceArchiveMigrationIntegrationTest {
  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Test
  void upgradePreservesExistingProfilesAndOnlyArchivedGroupsReleaseTheirName() {
    configuration().target("56").load().migrate();
    JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    UUID warehouse = UUID.randomUUID();
    UUID worker = UUID.randomUUID();
    UUID workerClass = UUID.randomUUID();
    UUID group = UUID.randomUUID();
    jdbc.update("insert into worker_class(id,version,revision_marker,name,sort_order,active) "
        + "values (?,0,?,'Archive class',0,true)", workerClass, UUID.randomUUID());
    jdbc.update("insert into worker(id,version,revision_marker,warehouse_id,display_name,active,credential_status) "
        + "values (?,7,?,?,'Retained worker',true,'NOT_CONFIGURED')", worker, UUID.randomUUID(), warehouse);
    jdbc.update("insert into worker_group(id,version,revision_marker,warehouse_id,worker_class_id,name,active,operational_status) "
        + "values (?,4,?,?,?,'Retained group',true,'AVAILABLE')", group, UUID.randomUUID(), warehouse, workerClass);
    String workerBefore = jdbc.queryForObject("select to_jsonb(w)::text from worker w where id=?", String.class, worker);
    String groupBefore = jdbc.queryForObject("select to_jsonb(g)::text from worker_group g where id=?", String.class, group);

    Flyway current = configuration().load();
    assertThat(current.migrate().migrationsExecuted).isOne();
    current.validate();
    assertThat(current.migrate().migrationsExecuted).isZero();
    assertThat(jdbc.queryForObject("select (to_jsonb(w)-'archived')::text from worker w where id=?", String.class, worker))
        .isEqualTo(workerBefore);
    assertThat(jdbc.queryForObject("select (to_jsonb(g)-'archived')::text from worker_group g where id=?", String.class, group))
        .isEqualTo(groupBefore);
    assertThatThrownBy(() -> jdbc.update("update worker set archived=true where id=?", worker))
        .isInstanceOf(DataIntegrityViolationException.class);
    jdbc.update("update worker_group set archived=true,active=false where id=?", group);
    jdbc.update("insert into worker_group(id,version,revision_marker,warehouse_id,worker_class_id,name,active,operational_status) "
        + "values (?,0,?,?,?,'Retained group',true,'AVAILABLE')", UUID.randomUUID(), UUID.randomUUID(), warehouse, workerClass);
    assertThat(jdbc.queryForObject("select count(*) from worker_group where name='Retained group'", Integer.class)).isEqualTo(2);
  }

  private org.flywaydb.core.api.configuration.FluentConfiguration configuration() {
    return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration").baselineOnMigrate(false).cleanDisabled(true).validateOnMigrate(true);
  }
}
