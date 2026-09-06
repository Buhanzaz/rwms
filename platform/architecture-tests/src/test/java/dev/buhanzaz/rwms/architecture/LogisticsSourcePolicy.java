package dev.buhanzaz.rwms.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Source-level guard for the isolated Stage 8 bounded context. */
final class LogisticsSourcePolicy {
  private static final Pattern LOGISTICS_PACKAGE =
      Pattern.compile(
          "(?m)^\\s*package\\s+dev\\.buhanzaz\\.rwms\\.logistics(?:\\.[A-Za-z_$][A-Za-z0-9_$.]*)?\\s*;");
  private static final Pattern FORBIDDEN_SERVICE_IMPORT =
      Pattern.compile(
          "(?m)^\\s*import\\s+(?:static\\s+)?dev\\.buhanzaz\\.rwms\\.(?:auth|taskboard|warehouse|asset|maintenance|inventory|media)\\.");
  private static final Pattern FORBIDDEN_RUNTIME_IMPORT =
      Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?(?:dev\\.buhanzaz\\.rwms\\.)?(?:panel|browser|legacy)\\.");
  private static final Pattern FORBIDDEN_RUNTIME_REFERENCE =
      Pattern.compile("(?i)wms-panel-old|old_db|localstorage|indexeddb");
  private static final Pattern FORBIDDEN_SERVICE_PROJECT =
      Pattern.compile(
          "project\\s*\\([^)]*:services:(?:auth|task-board|warehouse|asset|maintenance|inventory|media)-service[^)]*\\)");
  private static final Pattern FORBIDDEN_SCOPE_LITERAL =
      Pattern.compile(
          "(?i)\\b[A-Za-z0-9_]*scope\\b\\s*(?:=|\\()\\s*\\\"(?:asset\\.internal|media(?!\\.logistics\\\")[A-Za-z0-9_.-]*|reservation[A-Za-z0-9_.-]*|company[A-Za-z0-9_.-]*|location(?:-?correction)?[A-Za-z0-9_.-]*)\\\"");
  private static final Pattern MAPPER = Pattern.compile("@(?:org\\.mapstruct\\.)?Mapper\\b");
  private static final Pattern MAPPING_TARGET =
      Pattern.compile("@(?:org\\.mapstruct\\.)?MappingTarget\\b");
  private static final Pattern LOW_LEVEL_SQL =
      Pattern.compile(
          "org\\.springframework\\.jdbc|\\bJdbcTemplate\\b|\\bJdbcClient\\b|java\\.sql\\.(?:Connection|Statement|ResultSet)");
  private static final Pattern NATIVE_JPA_QUERY =
      Pattern.compile("\\bnativeQuery\\s*=\\s*true\\b");
  private static final Pattern NATIVE_JPA_QUERY_DETAILS =
      Pattern.compile(
          "(?s)@(?:org\\.springframework\\.data\\.jpa\\.repository\\.)?Query\\s*\\(\\s*"
              + "value\\s*=\\s*(?<literal>\"\"\".*?\"\"\"|\"(?:\\\\.|[^\"\\\\])*\")\\s*,\\s*"
              + "nativeQuery\\s*=\\s*true\\s*\\)\\s*"
              + "(?:[A-Za-z_$][A-Za-z0-9_$.<>?,\\[\\] ]*\\s+)?"
              + "(?<method>[A-Za-z_$][A-Za-z0-9_$]*)\\s*\\([^;]*?\\)\\s*;");
  private static final String OAUTH_HTTP_TRANSPORT =
      "src/main/java/dev/buhanzaz/rwms/logistics/integration/LogisticsOAuthHttpTransport.java";
  private static final String SINGLETON_TOKEN_SCOPE_INVARIANT =
      "getAccessToken().getScopes().equals(Set.of(requiredScope))";
  private static final Pattern SCOPE_DECLARATION =
      Pattern.compile(
          "(?m)private\\s+static\\s+final\\s+String\\s+[A-Z_]*_SCOPE\\s*=\\s*\\\"([^\\\"]+)\\\"");
  private static final Map<String, Set<String>> REQUIRED_OWNER_SCOPES =
      Map.of(
          "src/main/java/dev/buhanzaz/rwms/logistics/integration/LogisticsWarehouseDependencyClient.java",
          Set.of(
              "warehouse.logistics",
              "warehouse.timezone.read",
              "warehouse.operation.mark",
              "warehouse.lifecycle.read",
              "warehouse.lifecycle.confirm"),
          "src/main/java/dev/buhanzaz/rwms/logistics/integration/LogisticsAssetOperationsDependencyClient.java",
          Set.of("asset.logistics"),
          "src/main/java/dev/buhanzaz/rwms/logistics/integration/LogisticsAssetOrderPresentationDependencyClient.java",
          Set.of("asset.logistics"),
          "src/main/java/dev/buhanzaz/rwms/logistics/integration/LogisticsMaintenanceDependencyClient.java",
          Set.of("maintenance.logistics"),
          "src/main/java/dev/buhanzaz/rwms/logistics/integration/LogisticsMediaDependencyClient.java",
          Set.of("media.logistics"),
          "src/main/java/dev/buhanzaz/rwms/logistics/integration/LogisticsTaskBoardDependencyClient.java",
          Set.of("task-board.logistics"));
  private static final Set<String> LOW_LEVEL_SQL_ADAPTERS =
      Set.of(
          "dev/buhanzaz/rwms/logistics/eventing/LogisticsEventStore.java",
          "dev/buhanzaz/rwms/logistics/eventing/LogisticsOutboxStore.java",
          "dev/buhanzaz/rwms/logistics/eventing/LogisticsRecoveryObservationStore.java",
          "dev/buhanzaz/rwms/logistics/eventing/LogisticsReplayVerifier.java",
          "dev/buhanzaz/rwms/logistics/eventing/LogisticsSanitizedDltStore.java",
          "dev/buhanzaz/rwms/logistics/eventing/inbound/LogisticsInboxProcessor.java",
          "dev/buhanzaz/rwms/logistics/eventing/inbound/LogisticsInboundStagingStore.java",
          "dev/buhanzaz/rwms/logistics/eventing/inbound/LogisticsInboundObservationStore.java",
          "dev/buhanzaz/rwms/logistics/eventing/inbound/LogisticsInboundGapRecoveryService.java",
          "dev/buhanzaz/rwms/logistics/eventing/inbound/LogisticsKafkaConsumerRecoveryMonitor.java",
          "dev/buhanzaz/rwms/logistics/inquiry/eventing/RentalInquiryBookedOutboxStore.java",
          "dev/buhanzaz/rwms/logistics/retention/persistence/LogisticsRetentionCandidateReader.java",
          "dev/buhanzaz/rwms/logistics/service/persistence/LogisticsWarehouseAdmissionPersistence.java",
          "dev/buhanzaz/rwms/logistics/service/persistence/LogisticsDocumentJournalReader.java",
          "dev/buhanzaz/rwms/logistics/service/persistence/LogisticsWarehouseLifecycleBlockerReader.java",
          "dev/buhanzaz/rwms/logistics/service/persistence/LogisticsWarehouseOperationMarkPersistence.java");
  private static final Map<String, Set<ApprovedNativeJpaQuery>> APPROVED_NATIVE_JPA_QUERIES =
      Map.ofEntries(
          Map.entry(
              "dev/buhanzaz/rwms/logistics/repository/LogisticsIdempotencyRecordRepository.java",
              Set.of(
                  approved(
                      "acquireTransactionLocks",
                      """
                      select 1 from (
                        select lock_key
                        from unnest(string_to_array(cast(:lockKeys as text), chr(31))) as keys(lock_key)
                        order by lock_key
                      ) ordered
                      cross join lateral pg_advisory_xact_lock(
                        hashtextextended(cast(ordered.lock_key as text), 0)) ignored
                      """))),
          Map.entry(
              "dev/buhanzaz/rwms/logistics/pricing/repository/RentalPricingSettingsRepository.java",
              Set.of(
                  approved(
                      "readEquipmentReceiptRates",
                      """
                      select settings.version as pricingVersion, rates.equipment_id as equipmentId,
                             rates.monthly_price_rubles as monthlyPriceRubles
                      from rental_pricing_settings settings
                      left join rental_pricing_equipment_rate rates on rates.settings_id = settings.id
                      where settings.id = :id
                      order by rates.equipment_id
                      """))),
          Map.entry(
              "dev/buhanzaz/rwms/logistics/order/repository/RentalOrderRepository.java",
              Set.of(
                  approved(
                      "findDuePaymentsForUpdate",
                      """
                      select orders.* from rental_order orders
                      where orders.status = 'SAVED' and orders.payment_state = 'PENDING'
                        and orders.payment_expires_at <= :timestamp
                        and not exists (
                          select 1 from rental_order_mutation_command command
                          where command.order_id = orders.id and command.state in ('PENDING', 'QUARANTINED'))
                        and not exists (
                          select 1 from customer_booking_mutation mutation
                          where mutation.order_id = orders.id and mutation.operation = 'CANCEL'
                            and mutation.state in ('PENDING', 'QUARANTINED'))
                        and not exists (
                          select 1 from shipment_furniture_movement_task replacement
                          where replacement.order_id = orders.id
                            and replacement.replacement_idempotency_key is not null
                            and replacement.replacement_completed_at is null
                            and replacement.replacement_rejected_at is null)
                      order by orders.payment_expires_at, orders.id
                      for update skip locked limit :batchSize
                      """))),
          Map.entry(
              "dev/buhanzaz/rwms/logistics/order/repository/RentalOrderPaymentReceiptRepository.java",
              Set.of(approved("currentDatabaseTimestamp", "select clock_timestamp()"))),
          Map.entry(
              "dev/buhanzaz/rwms/logistics/order/recovery/RentalOrderMutationCommandRepository.java",
              Set.of(
                  approved(
                      "findDueForUpdate",
                      """
                      select command.*
                      from rental_order_mutation_command command
                      where command.state = 'PENDING'
                        and command.next_attempt_at <= :timestamp
                        and (command.lease_until is null or command.lease_until <= :timestamp)
                      order by command.next_attempt_at, command.created_at, command.id
                      for update skip locked
                      limit :batchSize
                      """),
                  approved("currentDatabaseTimestamp", "select clock_timestamp()"))),
          Map.entry(
              "dev/buhanzaz/rwms/logistics/customer/repository/CustomerBookingMutationRepository.java",
              Set.of(
                  approved(
                      "findDueForUpdate",
                      """
                      select mutation.*
                      from customer_booking_mutation mutation
                      where mutation.operation = 'CANCEL'
                        and mutation.state = 'PENDING'
                        and mutation.next_attempt_at <= :timestamp
                        and (mutation.lease_until is null or mutation.lease_until <= :timestamp)
                      order by mutation.next_attempt_at, mutation.created_at, mutation.id
                      for update skip locked
                      limit :batchSize
                      """),
                  approved("currentDatabaseTimestamp", "select clock_timestamp()"))),
          Map.entry(
              "dev/buhanzaz/rwms/logistics/customer/repository/CustomerRentalSessionRepository.java",
              Set.of(
                  approved(
                      "findPaymentExpiryScope",
                      """
                      select session.inquiry_id as inquiryId, booking.id as bookingId,
                             booking.presentation_id as presentationId,
                             booking.presentation_revision as presentationRevision,
                             session.delivery_slot_id as deliverySlotId
                      from customer_rental_session session
                      join client_presentation presentation on presentation.inquiry_id = session.inquiry_id
                      join presentation_booking booking on booking.presentation_id = presentation.id
                      where booking.order_id = :orderId
                        and (booking.id = session.booking_id or booking.idempotency_key = session.checkout_command_key)
                      """),
                  approved(
                      "findDueCheckoutRecoveryForUpdate",
                      """
                      select session.*
                      from customer_rental_session session
                      where session.state = 'CHECKOUT_PENDING'
                        and session.booking_id is not null
                        and session.presentation_token is not null
                        and session.recovery_quarantined_at is null
                        and session.recovery_next_attempt_at <= :timestamp
                        and (
                          session.recovery_lease_until is null
                          or session.recovery_lease_until <= :timestamp
                        )
                      order by session.recovery_next_attempt_at, session.updated_at, session.id
                      for update skip locked
                      limit :batchSize
                      """),
                  approved("currentDatabaseTimestamp", "select clock_timestamp()"))),
          Map.entry(
              "dev/buhanzaz/rwms/logistics/customer/repository/CustomerNotificationRepository.java",
              Set.of(approved("currentDatabaseTimestamp", "select clock_timestamp()"))));
  private static final Set<String> REQUIRED_INFRASTRUCTURE_SOURCES =
      Set.of(
          "src/main/java/dev/buhanzaz/rwms/logistics/eventing/LogisticsEventStore.java",
          "src/main/java/dev/buhanzaz/rwms/logistics/eventing/LogisticsOutboxStore.java",
          "src/main/java/dev/buhanzaz/rwms/logistics/eventing/LogisticsReplayVerifier.java",
          "src/main/java/dev/buhanzaz/rwms/logistics/eventing/LogisticsSanitizedDltStore.java",
          "src/main/java/dev/buhanzaz/rwms/logistics/integration/LogisticsDependencyGateway.java",
          "src/main/java/dev/buhanzaz/rwms/logistics/integration/HttpLogisticsDependencyGateway.java");
  private static final Set<String> REQUIRED_SCHEMA_TABLES =
      Set.of(
          "event_stream_head",
          "domain_event",
          "projection_checkpoint",
          "outbox_event",
          "inbox_message",
          "consumer_aggregate_checkpoint",
          "version_gap_quarantine",
          "sanitized_dead_letter");

  private LogisticsSourcePolicy() {}

  static void assertSafe(Path serviceRoot) throws IOException {
    assertSourceBoundarySafe(serviceRoot);
    if (!Files.exists(serviceRoot)) {
      return;
    }

    var violations = new ArrayList<String>();
    for (String requiredSource : REQUIRED_INFRASTRUCTURE_SOURCES) {
      if (!Files.isRegularFile(serviceRoot.resolve(requiredSource))) {
        violations.add(serviceRoot.resolve(requiredSource) + ": required local Stage 8 infrastructure is absent");
      }
    }

    Path migration = serviceRoot.resolve("src/main/resources/db/migration/V1__logistics_schema.sql");
    if (!Files.isRegularFile(migration)) {
      violations.add(migration + ": logistics Flyway V1 is required");
    } else {
      String source = read(migration);
      for (String table : REQUIRED_SCHEMA_TABLES) {
        if (!source.contains("CREATE TABLE public." + table)) {
          violations.add(migration + ": missing local " + table + " infrastructure");
        }
      }
    }

    assertExactDependencyScopes(serviceRoot, violations);

    failIfNeeded(violations);
  }

  static void assertSourceBoundarySafe(Path serviceRoot) throws IOException {
    if (!Files.exists(serviceRoot)) {
      return;
    }

    var violations = new ArrayList<String>();
    Path sourceRoot = serviceRoot.resolve("src/main/java");
    if (Files.isDirectory(sourceRoot)) {
      try (var files = Files.walk(sourceRoot)) {
        files
            .filter(path -> path.toString().endsWith(".java"))
            .forEach(path -> inspectJava(sourceRoot, path, read(path), violations));
      }
    }

    Path buildFile = serviceRoot.resolve("build.gradle.kts");
    if (Files.isRegularFile(buildFile) && FORBIDDEN_SERVICE_PROJECT.matcher(read(buildFile)).find()) {
      violations.add(buildFile + ": logistics-service must not depend on another service project");
    }

    failIfNeeded(violations);
  }

  private static void inspectJava(
      Path sourceRoot, Path path, String source, List<String> violations) {
    if (!LOGISTICS_PACKAGE.matcher(source).find()) {
      violations.add(path + ": production source must use the logistics package root");
    }
    if (FORBIDDEN_SERVICE_IMPORT.matcher(source).find()) {
      violations.add(path + ": logistics source imports another service implementation");
    }
    if (FORBIDDEN_RUNTIME_IMPORT.matcher(source).find()
        || FORBIDDEN_RUNTIME_REFERENCE.matcher(source).find()) {
      violations.add(path + ": logistics source must not depend on panel, browser, legacy or browser-store runtime");
    }
    if (FORBIDDEN_SCOPE_LITERAL.matcher(source).find()) {
      violations.add(path + ": logistics source requests a forbidden broad or unowned scope");
    }

    String relative = sourceRoot.relativize(path).toString().replace('\\', '/');
    if (LOW_LEVEL_SQL.matcher(source).find() && !LOW_LEVEL_SQL_ADAPTERS.contains(relative)) {
      violations.add(
          path
              + ": low-level SQL is restricted to exact technical event/recovery-observation, "
              + "inquiry-outbox, warehouse-admission, warehouse-blocker and warehouse-mark adapters");
    }
    long nativeQueries = NATIVE_JPA_QUERY.matcher(source).results().count();
    Set<ApprovedNativeJpaQuery> actualNativeQueries = nativeJpaQueries(source);
    Set<ApprovedNativeJpaQuery> approvedNativeQueries =
        APPROVED_NATIVE_JPA_QUERIES.getOrDefault(relative, Set.of());
    if (nativeQueries != actualNativeQueries.size()
        || !actualNativeQueries.equals(approvedNativeQueries)) {
      violations.add(
          path
              + ": native JPA SQL is restricted to exact approved repository methods and queries");
    }
    if (MAPPER.matcher(source).find()) {
      if (!relative.contains("/mapper/") && !relative.contains("/mapping/")) {
        violations.add(path + ": MapStruct mapper must live in mapper or mapping package");
      }
      if (MAPPING_TARGET.matcher(source).find()) {
        violations.add(path + ": MapStruct mapper must not mutate mapping targets");
      }
    }
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot read " + path, exception);
    }
  }

  private static void assertExactDependencyScopes(Path serviceRoot, List<String> violations) {
    Path transport = serviceRoot.resolve(OAUTH_HTTP_TRANSPORT);
    if (!Files.isRegularFile(transport)) {
      violations.add(transport + ": singleton-token transport is absent");
    } else if (!read(transport).contains(SINGLETON_TOKEN_SCOPE_INVARIANT)) {
      violations.add(transport + ": transport must verify one exact receiver scope per token");
    }

    for (Map.Entry<String, Set<String>> owner : REQUIRED_OWNER_SCOPES.entrySet()) {
      Path source = serviceRoot.resolve(owner.getKey());
      if (!Files.isRegularFile(source)) {
        violations.add(source + ": exact dependency-owner client is absent");
        continue;
      }
      Set<String> actualScopes = declaredScopes(read(source));
      if (!owner.getValue().equals(actualScopes)) {
        violations.add(
            source
                + ": declared dependency scopes must equal "
                + owner.getValue()
                + " but were "
                + actualScopes);
      }
    }
  }

  private static Set<String> declaredScopes(String source) {
    var scopes = new java.util.HashSet<String>();
    var matcher = SCOPE_DECLARATION.matcher(source);
    while (matcher.find()) {
      scopes.add(matcher.group(1));
    }
    return Set.copyOf(scopes);
  }

  private static Set<ApprovedNativeJpaQuery> nativeJpaQueries(String source) {
    var queries = new HashSet<ApprovedNativeJpaQuery>();
    var matcher = NATIVE_JPA_QUERY_DETAILS.matcher(source);
    while (matcher.find()) {
      queries.add(approved(matcher.group("method"), javaStringLiteral(matcher.group("literal"))));
    }
    return Set.copyOf(queries);
  }

  private static ApprovedNativeJpaQuery approved(String methodName, String sql) {
    return new ApprovedNativeJpaQuery(methodName, normalizeWhitespace(sql));
  }

  private static String javaStringLiteral(String literal) {
    if (literal.startsWith("\"\"\"")) {
      return literal.substring(3, literal.length() - 3);
    }
    return literal.substring(1, literal.length() - 1).replace("\\\"", "\"");
  }

  private static String normalizeWhitespace(String value) {
    return value.trim().replaceAll("\\s+", " ");
  }

  private record ApprovedNativeJpaQuery(String methodName, String sql) {}

  private static void failIfNeeded(List<String> violations) {
    if (!violations.isEmpty()) {
      throw new AssertionError(String.join(System.lineSeparator(), violations));
    }
  }
}
