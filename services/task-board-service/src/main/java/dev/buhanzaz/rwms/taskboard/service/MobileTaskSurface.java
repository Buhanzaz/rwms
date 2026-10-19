package dev.buhanzaz.rwms.taskboard.service;

/**
 * Native task client whose OAuth scope and queue role are enforced independently.
 *
 * <p>Both applications authenticate a {@code WORKER} principal, but a surface is fixed by the
 * controller route and never selected by request data.
 */
public enum MobileTaskSurface {
  /** WorkerApp: ordinary work plus the secondary slinger role on active driver tasks. */
  WORKER("worker.tasks"),

  /** DriverApp: primary driver and other primary warehouse queue bindings. */
  DRIVER("driver.tasks");

  private final String scope;

  MobileTaskSurface(String scope) {
    this.scope = scope;
  }

  /** Returns the sole OAuth task scope accepted by this route family. */
  public String scope() {
    return scope;
  }
}
