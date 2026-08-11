package dev.buhanzaz.rwms.logistics.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Validated name and phone value stored inside an existing logistics client or rental order. */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AdditionalContact {
  @Column(name = "contact_name", nullable = false, length = 255)
  private String name;

  @Column(name = "phone", nullable = false, length = 32)
  private String phone;

  /** Creates a normalized additional contact without changing the primary contact projection. */
  public static AdditionalContact create(String name, String phone) {
    AdditionalContact contact = new AdditionalContact();
    contact.name = requireName(name);
    contact.phone = PhoneNumberNormalizer.normalizeRequired(phone);
    return contact;
  }

  private static String requireName(String value) {
    String normalized = value == null ? "" : value.trim().replaceAll("[\\p{Z}\\s]+", " ");
    if (normalized.isEmpty() || normalized.length() > 255) {
      throw new IllegalArgumentException("additional contact name is invalid");
    }
    return normalized;
  }

  @Override
  public final boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof AdditionalContact contact)) return false;
    return Objects.equals(name, contact.name) && Objects.equals(phone, contact.phone);
  }

  @Override
  public final int hashCode() {
    return Objects.hash(name, phone);
  }
}
