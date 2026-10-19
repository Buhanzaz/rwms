package dev.buhanzaz.rwms.logistics.order.service;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import org.springframework.http.HttpStatus;

/**
 * Builds the stable rental-order problem responses used by local fences and validated dependency
 * failures. This type has no persistence, authorization, or workflow state.
 */
final class RentalOrderProblems {
  private RentalOrderProblems() {}

  static OrderProblemException notFound() {
    return new OrderProblemException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Заказ не найден");
  }

  static OrderProblemException conflict(String code, String message) {
    return new OrderProblemException(HttpStatus.CONFLICT, code, message);
  }

  static OrderProblemException invalidDependencyResponse() {
    return new OrderProblemException(
        HttpStatus.BAD_GATEWAY,
        "ORDER_ASSET_RESPONSE_INVALID",
        "Складской сервис вернул некорректный ответ");
  }

  static OrderProblemException dependencyProblem(LogisticsDependencyException exception) {
    String code = exception.dependencyCode();
    if (exception.kind() == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
      return switch (code == null ? "" : code) {
        case "UNIT_WAREHOUSE_MISMATCH" ->
            conflict(code, "Бытовка находится на другом складе");
        case "UNIT_NOT_AVAILABLE" ->
            conflict(code, "Бытовка больше не доступна для заказа");
        case "UNIT_NOT_EDITABLE" ->
            conflict(code, "Наполнение этой бытовки нельзя изменить в заказе");
        case "EQUIPMENT_QUANTITY_CONFLICT" ->
            conflict(code, "Количество оборудования изменилось параллельно");
        case "INSUFFICIENT_STOCK" ->
            conflict(code, "Недостаточный остаток оборудования на складе");
        case "ASSET_NOT_FOUND" ->
            new OrderProblemException(
                HttpStatus.NOT_FOUND, "ORDER_UNIT_NOT_FOUND", "Бытовка не найдена");
        default ->
            new OrderProblemException(
                HttpStatus.CONFLICT,
                "ORDER_ASSET_COMMAND_REJECTED",
                "Складской сервис отклонил операцию заказа");
      };
    }
    return new OrderProblemException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "ORDER_ASSET_SERVICE_UNAVAILABLE",
        "Складской сервис временно недоступен");
  }

  static void requireVersion(RentalOrder order, long expectedVersion) {
    if (expectedVersion < 0 || order.getVersion() != expectedVersion) {
      throw conflict(
          "ORDER_VERSION_CONFLICT",
          "Заказ был изменён параллельно; обновите данные и повторите действие");
    }
  }
}
