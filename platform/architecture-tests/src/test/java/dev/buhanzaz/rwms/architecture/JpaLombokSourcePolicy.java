package dev.buhanzaz.rwms.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class JpaLombokSourcePolicy {
  private static final Pattern ENTITY =
      Pattern.compile("@(?:jakarta\\.persistence\\.)?Entity\\b", Pattern.MULTILINE);
  private static final Pattern FORBIDDEN_ENTITY_GENERATOR_ANNOTATION =
      Pattern.compile(
          "@(?:lombok\\.)?(?:experimental\\.)?"
              + "(Data|Value|Builder|SuperBuilder|EqualsAndHashCode|ToString)\\b",
          Pattern.MULTILINE);
  private static final Pattern TYPE_DECLARATION = Pattern.compile("\\b(class|record|interface)\\s+\\w+");
  private static final Pattern SETTER_ANNOTATION =
      Pattern.compile("@(?:lombok\\.)?Setter(?:\\([^)]*\\))?");
  private static final Pattern PROTECTED_FIELD_ANNOTATION =
      Pattern.compile(
          "@(?:jakarta\\.persistence\\.)?(?:Id|EmbeddedId|Version)\\b"
              + "|@(?:org\\.hibernate\\.annotations\\.)?(?:CreationTimestamp|UpdateTimestamp)\\b"
              + "|@(?:org\\.springframework\\.data\\.annotation\\.)?"
              + "(?:CreatedDate|LastModifiedDate|CreatedBy|LastModifiedBy)\\b");
  private static final Pattern FIELD_NAME =
      Pattern.compile("\\b([A-Za-z_$][A-Za-z0-9_$]*)\\s*(?:=[^;]*)?;\\s*$");
  private static final Pattern METHOD_DECLARATION =
      Pattern.compile(
          ".*\\b[A-Za-z_$][A-Za-z0-9_$]*\\s*\\([^;]*\\)\\s*"
              + "(?:throws\\s+[^\\{]+)?\\{?\\s*$");
  private static final Pattern IDENTITY_OR_VERSION_FIELD =
      Pattern.compile("(?:id|.*Id|version|revision|.*Version|.*Revision)");
  private static final Pattern AUDIT_OR_TIMESTAMP_FIELD =
      Pattern.compile(
          "(?:.*At|timestamp|.*Timestamp|created|updated|modified|recorded|occurred|processed|deleted)"
              + "|(?:created|updated|modified|recorded|occurred|processed|deleted)"
              + "(?:On|Date|Time|By)");
  private static final Pattern DOMAIN_INVARIANT_FIELD =
      Pattern.compile(
          "(?:(?i:status|state|active|enabled|deleted|code|type|role)|.*Status|.*State)");
  private JpaLombokSourcePolicy() {}

  static void assertSafe(Path sourceRoot) throws IOException {
    var violations = new ArrayList<String>();
    try (var files = Files.walk(sourceRoot)) {
      files
          .filter(path -> path.toString().endsWith(".java"))
          .forEach(path -> inspect(path, read(path), violations));
    }
    if (!violations.isEmpty()) {
      throw new AssertionError(String.join(System.lineSeparator(), violations));
    }
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot read " + path, exception);
    }
  }

  private static void inspect(Path path, String source, List<String> violations) {
    if (!ENTITY.matcher(source).find()) {
      return;
    }
    inspectEntityAnnotationsAndFields(path, source, violations);
  }

  private static void inspectEntityAnnotationsAndFields(
      Path path, String source, List<String> violations) {
    var pendingEntity = false;
    var pendingForbiddenTypeAnnotation = false;
    var pendingClassSetter = false;
    var inEntity = false;
    var entityBracesStarted = false;
    var entityBraceDepth = 0;
    var pendingSetter = false;
    var pendingProtectedFieldAnnotation = false;
    var pendingFieldDeclaration = new StringBuilder();
    for (var line : source.lines().toList()) {
      var trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.startsWith("//")) {
        continue;
      }

      if (!inEntity) {
        pendingEntity |= ENTITY.matcher(trimmed).find();
        pendingForbiddenTypeAnnotation |=
            FORBIDDEN_ENTITY_GENERATOR_ANNOTATION.matcher(trimmed).find();
        pendingClassSetter |= SETTER_ANNOTATION.matcher(trimmed).find();
      }
      if (!inEntity && TYPE_DECLARATION.matcher(trimmed).find()) {
        if (pendingEntity) {
          if (pendingForbiddenTypeAnnotation) {
            violations.add(path + ": JPA entity uses forbidden Lombok type annotation");
          }
          if (pendingClassSetter) {
            violations.add(path + ": JPA entity uses class-level Lombok @Setter");
          }
          inEntity = true;
        } else {
          pendingForbiddenTypeAnnotation = false;
          pendingClassSetter = false;
        }
      }

      if (inEntity && SETTER_ANNOTATION.matcher(trimmed).find()) {
        pendingSetter = true;
      }
      if (inEntity) {
        pendingProtectedFieldAnnotation |= PROTECTED_FIELD_ANNOTATION.matcher(trimmed).find();
      }
      if (inEntity && pendingSetter) {
        pendingFieldDeclaration.append(' ').append(trimmed);
      }
      if (inEntity && FORBIDDEN_ENTITY_GENERATOR_ANNOTATION.matcher(trimmed).find()) {
        violations.add(path + ": JPA entity body uses forbidden Lombok generator annotation");
      }
      if (inEntity && trimmed.endsWith(";")) {
        if (pendingSetter) {
          var fieldName = extractFieldName(pendingFieldDeclaration.toString());
          if (pendingProtectedFieldAnnotation || isProtectedFieldName(fieldName)) {
            violations.add(
                path
                    + ": Lombok @Setter is forbidden on protected JPA field "
                    + fieldName
                    + "; use an explicit domain method");
          }
        }
        clearPendingFieldState(pendingFieldDeclaration);
        pendingSetter = false;
        pendingProtectedFieldAnnotation = false;
      } else if (inEntity
          && (pendingSetter || pendingProtectedFieldAnnotation)
          && !trimmed.startsWith("@")
          && METHOD_DECLARATION.matcher(trimmed).matches()) {
        clearPendingFieldState(pendingFieldDeclaration);
        pendingSetter = false;
        pendingProtectedFieldAnnotation = false;
      }

      if (inEntity) {
        entityBraceDepth += count(trimmed, '{') - count(trimmed, '}');
        entityBracesStarted |= trimmed.indexOf('{') >= 0;
        if (entityBracesStarted && entityBraceDepth == 0) {
          pendingEntity = false;
          pendingForbiddenTypeAnnotation = false;
          pendingClassSetter = false;
          pendingSetter = false;
          pendingProtectedFieldAnnotation = false;
          clearPendingFieldState(pendingFieldDeclaration);
          inEntity = false;
          entityBracesStarted = false;
        }
      }
    }
  }

  private static String extractFieldName(String declaration) {
    var matcher = FIELD_NAME.matcher(declaration);
    return matcher.find() ? matcher.group(1) : "<unknown>";
  }

  private static boolean isProtectedFieldName(String fieldName) {
    return "<unknown>".equals(fieldName)
        || IDENTITY_OR_VERSION_FIELD.matcher(fieldName).matches()
        || AUDIT_OR_TIMESTAMP_FIELD.matcher(fieldName).matches()
        || DOMAIN_INVARIANT_FIELD.matcher(fieldName).matches();
  }

  private static void clearPendingFieldState(StringBuilder pendingFieldDeclaration) {
    pendingFieldDeclaration.setLength(0);
  }

  private static int count(String value, char character) {
    return (int) value.chars().filter(current -> current == character).count();
  }
}
