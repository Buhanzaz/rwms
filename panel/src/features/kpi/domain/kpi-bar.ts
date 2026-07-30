export type KpiBarRange = {
  fromPercent: number
  toPercent: number
  color: string
}

export function clampKpiValue(value: number) {
  return Math.min(100, Math.max(0, value))
}

export function barFillSegments(
  ranges: KpiBarRange[],
  value: number
): KpiBarRange[] {
  const end = clampKpiValue(value)

  return ranges.flatMap((range) => {
    const clippedEnd = Math.min(range.toPercent, end)
    if (clippedEnd <= range.fromPercent) return []

    return [{ ...range, toPercent: clippedEnd }]
  })
}
