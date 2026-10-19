package dev.buhanzaz.rwms.taskboard.domain;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.hibernate.annotations.IdGeneratorType;

/** Generates a random UUID unless a reviewed import explicitly assigned the identifier. */
@IdGeneratorType(AssignedOrGeneratedUuidGenerator.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface AssignedOrGeneratedUuid {}
