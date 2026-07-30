package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

/** One global palette for the four calculated repair-complexity values. */
@Entity
@Table(name = "repair_complexity_colors")
public class RepairComplexityColors {
  public static final UUID SINGLETON_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");
  public static final String DEFAULT_LIGHT_COLOR = "#22C55E";
  public static final String DEFAULT_MEDIUM_COLOR = "#EAB308";
  public static final String DEFAULT_COMPLEX_COLOR = "#F97316";
  public static final String DEFAULT_CAPITAL_COLOR = "#DC2626";

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "light_color", nullable = false, length = 7)
  private String lightColor;

  @Column(name = "medium_color", nullable = false, length = 7)
  private String mediumColor;

  @Column(name = "complex_color", nullable = false, length = 7)
  private String complexColor;

  @Column(name = "capital_color", nullable = false, length = 7)
  private String capitalColor;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected RepairComplexityColors() {}

  public static RepairComplexityColors defaults() {
    RepairComplexityColors value = new RepairComplexityColors();
    value.id = SINGLETON_ID;
    value.lightColor = DEFAULT_LIGHT_COLOR;
    value.mediumColor = DEFAULT_MEDIUM_COLOR;
    value.complexColor = DEFAULT_COMPLEX_COLOR;
    value.capitalColor = DEFAULT_CAPITAL_COLOR;
    return value;
  }

  public void replace(
      String lightColor, String mediumColor, String complexColor, String capitalColor) {
    this.lightColor = color(lightColor);
    this.mediumColor = color(mediumColor);
    this.complexColor = color(complexColor);
    this.capitalColor = color(capitalColor);
  }

  public String color(RepairComplexity complexity) {
    return switch (complexity) {
      case LIGHT -> lightColor;
      case MEDIUM -> mediumColor;
      case COMPLEX -> complexColor;
      case CAPITAL -> capitalColor;
    };
  }

  @PrePersist
  void beforeInsert() {
    replace(lightColor, mediumColor, complexColor, capitalColor);
    OffsetDateTime now = MaintenanceTime.now();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void beforeUpdate() {
    replace(lightColor, mediumColor, complexColor, capitalColor);
    updatedAt = MaintenanceTime.now();
  }

  private static String color(String value) {
    String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    if (!normalized.matches("^#[0-9A-F]{6}$")) {
      throw new IllegalArgumentException("Repair complexity color must use #RRGGBB");
    }
    return normalized;
  }

  public UUID getId() {
    return id;
  }

  public long getVersion() {
    return version;
  }

  public String getLightColor() {
    return lightColor;
  }

  public String getMediumColor() {
    return mediumColor;
  }

  public String getComplexColor() {
    return complexColor;
  }

  public String getCapitalColor() {
    return capitalColor;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
