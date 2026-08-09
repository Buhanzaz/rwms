package dev.buhanzaz.rwms.auth.eventing;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores private auth-subject profile data and a revision/hash used for internal consistency.
 *
 * <p>Profile content remains in the auth service's private vault. Event facts may refer only to
 * the opaque revision needed to indicate that authorization-adjacent projection data changed.
 */
@Service
@RequiredArgsConstructor
public class AuthSubjectProfileStore {

    private final JdbcTemplate jdbc;

    /**
     * Replaces one subject's private profile and creates a new revision and canonical hash.
     *
     * @param subjectId auth-subject identifier
     * @param username subject login identifier
     * @param firstName private profile attribute
     * @param lastName private profile attribute
     * @param email private profile attribute
     * @param timeZoneId private profile attribute
     * @param externalWorkerId private worker linkage when applicable
     * @return stored private profile metadata
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Profile replace(
            UUID subjectId,
            String username,
            String firstName,
            String lastName,
            String email,
            String timeZoneId,
            String externalWorkerId) {
        OffsetDateTime now = databaseNow();
        UUID revision = UUID.randomUUID();
        String hash = AuthEventStore.sha256(canonical(
                        username, firstName, lastName, email, timeZoneId, externalWorkerId)
                .getBytes(StandardCharsets.UTF_8));
        jdbc.update(
                """
                insert into auth_subject_pii(
                    subject_id, username, first_name, last_name, email, time_zone_id,
                    external_worker_id, profile_revision, profile_sha256, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (subject_id) do update
                    set username = excluded.username,
                        first_name = excluded.first_name,
                        last_name = excluded.last_name,
                        email = excluded.email,
                        time_zone_id = excluded.time_zone_id,
                        external_worker_id = excluded.external_worker_id,
                        profile_revision = excluded.profile_revision,
                        profile_sha256 = excluded.profile_sha256,
                        updated_at = excluded.updated_at
                """,
                subjectId,
                username,
                firstName,
                lastName,
                email,
                timeZoneId,
                externalWorkerId,
                revision,
                hash,
                now,
                now);
        return new Profile(
                subjectId,
                username,
                firstName,
                lastName,
                email,
                timeZoneId,
                externalWorkerId,
                revision,
                hash);
    }

    /**
     * Returns the private profile required by an internal auth operation.
     *
     * @param subjectId auth-subject identifier
     * @return private profile metadata
     * @throws IllegalStateException when the vault entry is missing
     */
    @Transactional(readOnly = true)
    public Profile require(UUID subjectId) {
        return jdbc.query(
                        """
                        select subject_id, username, first_name, last_name, email, time_zone_id,
                               external_worker_id, profile_revision, profile_sha256
                          from auth_subject_pii
                         where subject_id = ?
                        """,
                        (result, row) -> new Profile(
                                result.getObject("subject_id", UUID.class),
                                result.getString("username"),
                                result.getString("first_name"),
                                result.getString("last_name"),
                                result.getString("email"),
                                result.getString("time_zone_id"),
                                result.getString("external_worker_id"),
                                result.getObject("profile_revision", UUID.class),
                                result.getString("profile_sha256")),
                        subjectId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Auth subject PII vault entry is missing"));
    }

    /**
     * Finds the subject behind a login identifier without exposing profile content.
     *
     * @param username login identifier to match case-insensitively
     * @return matching subject identifier, if present
     */
    @Transactional(readOnly = true)
    public Optional<UUID> findSubjectIdByUsername(String username) {
        return jdbc.query(
                        "select subject_id from auth_subject_pii where lower(username) = lower(?)",
                        (result, row) -> result.getObject(1, UUID.class),
                        username)
                .stream()
                .findFirst();
    }

    /**
     * Finds a worker subject from its external linkage identifier.
     *
     * @param workerId external worker linkage identifier
     * @return matching subject identifier, if present
     */
    @Transactional(readOnly = true)
    public Optional<UUID> findSubjectIdByExternalWorkerId(String workerId) {
        return jdbc.query(
                        "select subject_id from auth_subject_pii where external_worker_id = ?",
                        (result, row) -> result.getObject(1, UUID.class),
                        workerId)
                .stream()
                .findFirst();
    }

    private OffsetDateTime databaseNow() {
        OffsetDateTime value = jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
        return value == null ? OffsetDateTime.now(ZoneOffset.UTC) : value;
    }

    private static String canonical(String... values) {
        return java.util.Arrays.stream(values)
                .map(value -> value == null ? "<null>" : value)
                .collect(java.util.stream.Collectors.joining("\u001f"));
    }

    /**
     * Internal private-profile vault entry with its revision and canonical hash.
     *
     * <p>This type must not cross the eventing boundary; only {@link #revision()} is used by safe
     * auth facts.
     *
     * @param subjectId auth-subject identifier
     * @param username private login identifier
     * @param firstName private profile attribute
     * @param lastName private profile attribute
     * @param email private profile attribute
     * @param timeZoneId private profile attribute
     * @param externalWorkerId private worker linkage, when present
     * @param revision opaque private-profile revision
     * @param hash canonical private-profile hash
     */
    public record Profile(
            UUID subjectId,
            String username,
            String firstName,
            String lastName,
            String email,
            String timeZoneId,
            String externalWorkerId,
            UUID revision,
            String hash) {}
}
