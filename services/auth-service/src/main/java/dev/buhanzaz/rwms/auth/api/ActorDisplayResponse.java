package dev.buhanzaz.rwms.auth.api;

import dev.buhanzaz.rwms.auth.domain.PrincipalType;
import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import java.util.UUID;

/**
 * Compact, non-secret identity data used to display the actor behind a cross-domain activity.
 *
 * <p>The response deliberately contains no password, access token, warehouse grant, or other
 * authorization secret.
 *
 * @param subjectId stable authorization-subject identifier
 * @param principalType kind of identity represented by the subject
 * @param globalRole interactive-user role, or {@code null} for a worker
 * @param username canonical login name
 * @param firstName optional given name
 * @param lastName optional family name
 * @param email optional email address
 */
public record ActorDisplayResponse(
        UUID subjectId,
        PrincipalType principalType,
        UserGlobalRole globalRole,
        String username,
        String firstName,
        String lastName,
        String email) {
}
