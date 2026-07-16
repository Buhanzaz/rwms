package dev.buhanzaz.rwms.auth.eventing;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserWarehouseAccessNoteStore {

    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.MANDATORY)
    public Note replace(UUID accessId, String comment) {
        OffsetDateTime now = databaseNow();
        UUID revision = UUID.randomUUID();
        String normalized = comment == null || comment.isBlank() ? null : comment.trim();
        String hash = AuthEventStore.sha256(
                (normalized == null ? "<null>" : normalized).getBytes(StandardCharsets.UTF_8));
        jdbc.update(
                """
                insert into user_warehouse_access_note(
                    access_id, comment_text, note_revision, note_sha256, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?)
                on conflict (access_id) do update
                    set comment_text = excluded.comment_text,
                        note_revision = excluded.note_revision,
                        note_sha256 = excluded.note_sha256,
                        updated_at = excluded.updated_at
                """,
                accessId,
                normalized,
                revision,
                hash,
                now,
                now);
        return new Note(accessId, normalized, revision);
    }

    @Transactional(readOnly = true)
    public Map<UUID, Note> findAllByUserId(UUID userId) {
        return jdbc.query(
                        """
                        select note.access_id, note.comment_text, note.note_revision
                          from user_warehouse_access_note note
                          join user_warehouse_access access on access.id = note.access_id
                         where access.user_id = ?
                        """,
                        (result, row) -> new Note(
                                result.getObject("access_id", UUID.class),
                                result.getString("comment_text"),
                                result.getObject("note_revision", UUID.class)),
                        userId)
                .stream()
                .collect(Collectors.toUnmodifiableMap(Note::accessId, Function.identity()));
    }

    private OffsetDateTime databaseNow() {
        OffsetDateTime value = jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
        return value == null ? OffsetDateTime.now(ZoneOffset.UTC) : value;
    }

    public record Note(UUID accessId, String comment, UUID revision) {}
}
