package dev.buhanzaz.rwms.auth.api;

import dev.buhanzaz.rwms.auth.domain.PrincipalType;
import dev.buhanzaz.rwms.auth.domain.UserGlobalRole;
import java.util.UUID;

public record ActorDisplayResponse(
        UUID subjectId,
        PrincipalType principalType,
        UserGlobalRole globalRole,
        String username,
        String firstName,
        String lastName,
        String email) {
}
