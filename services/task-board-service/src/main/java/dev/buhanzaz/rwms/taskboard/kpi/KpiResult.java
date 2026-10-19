package dev.buhanzaz.rwms.taskboard.kpi;

import java.math.BigDecimal;

/** Calculated KPI, speed, and utilization values for one evidence interval. */
public record KpiResult(BigDecimal kpi, BigDecimal speed, BigDecimal utilization) {}
