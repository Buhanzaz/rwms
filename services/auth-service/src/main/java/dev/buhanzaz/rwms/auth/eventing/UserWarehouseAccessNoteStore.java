package dev.buhanzaz.rwms.auth.eventing;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains private notes attached to user warehouse-access grants.
 *
 * <p>Notes are kept outside authorization fact payloads. Eventing consumers receive only the
 * opaque note revision, allowing safe invalidation without distributing comment content.
 */
@Service
@RequiredArgsConstructor
public class UserWarehouseAccessNoteStore {

    private final JdbcTemplate jdbc;

    /**
     * Replaces one grant's private note and creates a new opaque revision.
     *
     * @param accessId warehouse-access grant identifier
     * @param comment optional internal note, normalized before storage
     * @return stored note with its new revision
     */
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

    /**
     * Loads notes for all warehouse grants belonging to one user.
     *
     * @param userId user aggregate identifier
     * @return immutable map indexed by warehouse-access grant identifier
     */
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

    /** Reads notes by the requested access identifiers in one vault query. */
    @Transactional(readOnly = true)
    public Map<UUID, Note> findAllByAccessIds(Collection<UUID> accessIds) {
        if (accessIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.execute((java.sql.Connection connection) -> {
            java.sql.Array identifiers = connection.createArrayOf("uuid", accessIds.toArray(UUID[]::new));
            try (PreparedStatement statement = connection.prepareStatement("""
                select access_id, comment_text, note_revision
                  from user_warehouse_access_note
                 where access_id = any (?)
                """)) {
                statement.setArray(1, identifiers);
                try (ResultSet result = statement.executeQuery()) {
                    var notes = new java.util.LinkedHashMap<UUID, Note>();
                    while (result.next()) {
                        Note note = new Note(result.getObject("access_id", UUID.class), result.getString("comment_text"), result.getObject("note_revision", UUID.class));
                        notes.put(note.accessId(), note);
                    }
                    return Map.copyOf(notes);
                }
            } finally {
                identifiers.free();
            }
        });
    }

    private OffsetDateTime databaseNow() {
        OffsetDateTime value = jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
        return value == null ? OffsetDateTime.now(ZoneOffset.UTC) : value;
    }

    /**
     * Private warehouse-access note and its safe invalidation revision.
     *
     * @param accessId warehouse-access grant identifier
     * @param comment internal note content; never included in an auth event fact
     * @param revision opaque revision exposed to safe facts
     */
    public record Note(UUID accessId, String comment, UUID revision) {}
}
