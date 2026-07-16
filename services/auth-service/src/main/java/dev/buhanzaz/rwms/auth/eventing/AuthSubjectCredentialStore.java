package dev.buhanzaz.rwms.auth.eventing;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthSubjectCredentialStore {

    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public void replace(UUID subjectId, String passwordHash, boolean active) {
        OffsetDateTime now = databaseNow();
        jdbc.update(
                """
                insert into auth_subject_credential(
                    subject_id, password_hash, credential_status,
                    credential_revision, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?)
                on conflict (subject_id) do update
                    set password_hash = excluded.password_hash,
                        credential_status = excluded.credential_status,
                        credential_revision = excluded.credential_revision,
                        updated_at = excluded.updated_at
                """,
                subjectId,
                passwordHash,
                active ? "ACTIVE" : "DISABLED",
                UUID.randomUUID(),
                now,
                now);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void changeStatus(UUID subjectId, boolean active) {
        int changed = jdbc.update(
                """
                update auth_subject_credential
                   set credential_status = ?, credential_revision = ?, updated_at = ?
                 where subject_id = ?
                """,
                active ? "ACTIVE" : "DISABLED",
                UUID.randomUUID(),
                databaseNow(),
                subjectId);
        if (changed != 1) {
            throw new IllegalStateException("Auth subject credential vault entry is missing");
        }
    }

    @Transactional(readOnly = true)
    public Credential require(UUID subjectId) {
        return jdbc.query(
                        """
                        select subject_id, password_hash, credential_status, credential_revision
                          from auth_subject_credential
                         where subject_id = ?
                        """,
                        (result, row) -> new Credential(
                                result.getObject("subject_id", UUID.class),
                                result.getString("password_hash"),
                                result.getString("credential_status"),
                                result.getObject("credential_revision", UUID.class)),
                        subjectId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Auth subject credential vault entry is missing"));
    }

    private OffsetDateTime databaseNow() {
        OffsetDateTime value = jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
        return value == null ? OffsetDateTime.now(ZoneOffset.UTC) : value;
    }

    public record Credential(UUID subjectId, String passwordHash, String status, UUID revision) {}
}
