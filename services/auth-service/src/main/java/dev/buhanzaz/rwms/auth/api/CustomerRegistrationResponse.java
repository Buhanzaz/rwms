package dev.buhanzaz.rwms.auth.api;

import java.util.UUID;

/**
 * Public non-sensitive result of customer self-registration.
 *
 * @param subjectId canonical auth subject identifier
 * @param username normalized login accepted by auth-service
 */
public record CustomerRegistrationResponse(UUID subjectId, String username) {}
