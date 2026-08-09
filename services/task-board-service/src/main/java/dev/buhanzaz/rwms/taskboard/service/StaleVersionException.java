package dev.buhanzaz.rwms.taskboard.service;

/** Specialized conflict raised when an expected optimistic version no longer matches. */
public class StaleVersionException extends ConflictException {
  public StaleVersionException(String resource) {
    super(resource + " был изменен другим пользователем");
  }
}
