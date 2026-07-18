package dev.buhanzaz.rwms.logistics.domain;

public enum LogisticsLineState {
  PENDING,
  DEPARTING,
  DEPARTED,
  ARRIVING,
  ARRIVED,
  CONFLICT,
  CANCELLED
}
