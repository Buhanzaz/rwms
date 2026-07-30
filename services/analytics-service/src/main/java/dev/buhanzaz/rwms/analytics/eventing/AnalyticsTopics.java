package dev.buhanzaz.rwms.analytics.eventing;

public final class AnalyticsTopics {
  public static final String INPUT = "rwms.task-board.group-kpi-day.v1";
  public static final String CONSUMER_GROUP = "analytics-projection-v1";
  public static final String DLT = INPUT + "." + CONSUMER_GROUP + ".dlt";

  private AnalyticsTopics() {}
}
