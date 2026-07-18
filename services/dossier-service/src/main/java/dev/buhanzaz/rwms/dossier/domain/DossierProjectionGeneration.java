package dev.buhanzaz.rwms.dossier.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "dossier_projection_generation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DossierProjectionGeneration {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "row_version", nullable = false)
  private long rowVersion;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 24)
  private DossierGenerationState state;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "activated_at")
  private OffsetDateTime activatedAt;

  @Column(name = "retired_at")
  private OffsetDateTime retiredAt;

  public static DossierProjectionGeneration building(OffsetDateTime now) {
    DossierProjectionGeneration generation = new DossierProjectionGeneration();
    generation.state = DossierGenerationState.BUILDING;
    generation.createdAt = DossierSourceFact.require(now, "now");
    return generation;
  }

  public void ready() {
    transition(DossierGenerationState.BUILDING, DossierGenerationState.READY);
  }

  public void activate(OffsetDateTime now) {
    transition(DossierGenerationState.READY, DossierGenerationState.ACTIVE);
    activatedAt = DossierSourceFact.require(now, "now");
  }

  public void reject() {
    if (state == DossierGenerationState.REJECTED) return;
    if (state != DossierGenerationState.BUILDING && state != DossierGenerationState.READY) {
      throw new IllegalStateException("Only an inactive generation can be rejected");
    }
    state = DossierGenerationState.REJECTED;
  }

  public void retire(OffsetDateTime now) {
    transition(DossierGenerationState.ACTIVE, DossierGenerationState.RETIRED);
    retiredAt = DossierSourceFact.require(now, "now");
  }

  private void transition(DossierGenerationState expected, DossierGenerationState target) {
    if (state != expected) throw new IllegalStateException("Invalid projection generation transition");
    state = target;
  }
}
