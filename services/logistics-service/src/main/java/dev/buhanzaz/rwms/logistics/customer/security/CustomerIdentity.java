package dev.buhanzaz.rwms.logistics.customer.security;

import java.util.UUID;

/** Authenticated CustomerApp identity after client, role, scope and subject validation. */
public record CustomerIdentity(UUID subjectId, String username) {}
