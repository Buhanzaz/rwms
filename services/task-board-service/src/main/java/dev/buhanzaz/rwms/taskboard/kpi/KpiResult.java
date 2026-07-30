package dev.buhanzaz.rwms.taskboard.kpi;

import java.math.BigDecimal;

public record KpiResult(BigDecimal kpi, BigDecimal speed, BigDecimal utilization) {}
