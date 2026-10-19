package dev.buhanzaz.rwms.taskboard.eventing;

import jakarta.persistence.EntityManager;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single persistence boundary for task-board event-sourced relational projections.
 * Command services mutate aggregate state in memory and delegate every JPA write here so the
 * projection update and event append remain in the same caller-owned PostgreSQL transaction.
 */
@Service
@RequiredArgsConstructor
public class TaskBoardProjectionWriter {
  private final EntityManager entityManager;

  @Transactional(propagation = Propagation.MANDATORY)
  public <T> T save(JpaRepository<T, UUID> repository, T value) {
    return repository.save(value);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public <T> T saveAndFlush(JpaRepository<T, UUID> repository, T value) {
    return repository.saveAndFlush(value);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public <T> T persistAndFlush(T value) {
    entityManager.persist(value);
    entityManager.flush();
    return value;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public <T> void saveAll(JpaRepository<T, UUID> repository, Iterable<T> values) {
    repository.saveAll(values);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public <T> void delete(JpaRepository<T, UUID> repository, T value) {
    repository.delete(value);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public <T> void deleteAll(JpaRepository<T, UUID> repository, Iterable<T> values) {
    repository.deleteAll(values);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void flush() {
    entityManager.flush();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void refresh(Object projection) {
    entityManager.refresh(projection);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void clear() {
    entityManager.clear();
  }
}
