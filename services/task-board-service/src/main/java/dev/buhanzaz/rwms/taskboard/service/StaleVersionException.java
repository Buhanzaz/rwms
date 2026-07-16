package dev.buhanzaz.rwms.taskboard.service;

public class StaleVersionException extends ConflictException {
  public StaleVersionException(String resource) {
    super(resource + " был изменен другим пользователем");
  }
}
