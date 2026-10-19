package dev.buhanzaz.rwms.taskboard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;

/** Global qualification class used by queues, groups, and worker eligibility. */
@Entity
@Table(name = "worker_class")
public class WorkerClass extends AbstractVersionedEntity {
  @Column(name = "revision_marker", nullable = false)
  private java.util.UUID revisionMarker = java.util.UUID.randomUUID();

  @NotBlank
  @Column(name = "name", nullable = false, length = 128)
  private String name;

  @Column(name = "description", length = 1000)
  private String description;

  @Column(name = "comment_text", length = 1000)
  private String comment;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(name = "active", nullable = false)
  private boolean active = true;

  @PrePersist
  @PreUpdate
  void normalize() {
    name = trim(name);
  }

  private String trim(String value) {
    return value == null ? null : value.trim();
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getComment() {
    return comment;
  }

  public void setComment(String comment) {
    this.comment = comment;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public void setSortOrder(int sortOrder) {
    this.sortOrder = sortOrder;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public void touch() {
    revisionMarker = java.util.UUID.randomUUID();
  }

  public java.util.UUID getRevisionMarker() {
    return revisionMarker;
  }
}
