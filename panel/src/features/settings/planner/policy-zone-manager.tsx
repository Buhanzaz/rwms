import { useCallback, useEffect, useMemo, useState } from "react"
import type { MultiPolygon } from "geojson"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import {
  Field,
  FieldDescription,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Separator } from "@/components/ui/separator"
import { Textarea } from "@/components/ui/textarea"
import { planningApi } from "./planning-api"
import type {
  PlannerWarehouseSettings,
  PolicyZoneInput,
  PolicyZoneKind,
  WarehousePolicyZone,
} from "./planning-types"
import { PolicyZoneMapEditor } from "./policy-zone-map-editor"
import {
  parsePolicyZoneGeometry,
  policyZoneGeometryText,
} from "./policy-zone-geometry"

const KIND_LABELS: Record<PolicyZoneKind, string> = {
  SPECIAL_PRICE: "Особая цена",
  FORBIDDEN: "Запрещено",
  NO_TRAILER: "Без прицепа",
}
const KIND_COLORS: Record<PolicyZoneKind, string> = {
  SPECIAL_PRICE: "#3B82F6",
  FORBIDDEN: "#EF4444",
  NO_TRAILER: "#F59E0B",
}
interface PolicyZoneDraft {
  id: string | null
  version: number | null
  name: string
  kind: PolicyZoneKind
  color: string
  geometryText: string
  geometry: MultiPolygon | null
  deliveryPriceRubles: string
  pickupPriceRubles: string
  createIntentKey: string
}

/** The admin editor keeps exact server geometry and the observed policy version. */
export default function PolicyZoneManager({
  warehouse,
  token,
}: {
  warehouse: PlannerWarehouseSettings
  token: string
}) {
  const [zones, setZones] = useState<WarehousePolicyZone[]>([])
  const [draft, setDraft] = useState<PolicyZoneDraft>(() => emptyDraft())
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<unknown>(null)
  const [commandError, setCommandError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [deleteCandidate, setDeleteCandidate] = useState<string | null>(null)

  const loadZones = useCallback(
    () => planningApi.listPolicyZones(token, warehouse.warehouse_id),
    [token, warehouse.warehouse_id]
  )

  useEffect(() => {
    let active = true
    void loadZones()
      .then((rows) => {
        if (active) setZones(rows)
      })
      .catch((cause: unknown) => {
        if (active) setLoadError(cause)
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [loadZones])

  const selected = useMemo(
    () => zones.find((zone) => zone.id === draft.id) ?? null,
    [draft.id, zones]
  )
  const selectZone = (zone: WarehousePolicyZone) => {
    setCommandError(null)
    setDeleteCandidate(null)
    setDraft(draftFromZone(zone))
  }
  const startCreate = () => {
    setCommandError(null)
    setDeleteCandidate(null)
    setDraft(emptyDraft())
  }
  const changeKind = (kind: PolicyZoneKind) => {
    setDraft((current) => ({
      ...current,
      kind,
      color: KIND_COLORS[kind],
      deliveryPriceRubles:
        kind === "SPECIAL_PRICE" ? current.deliveryPriceRubles : "",
      pickupPriceRubles:
        kind === "SPECIAL_PRICE" ? current.pickupPriceRubles : "",
    }))
  }
  const changeGeometryText = (geometryText: string) => {
    setDraft((current) => {
      let geometry = current.geometry
      try {
        geometry = parsePolicyZoneGeometry(geometryText)
      } catch {
        // Keep the last valid preview while the operator finishes exact JSON editing.
      }
      return { ...current, geometryText, geometry }
    })
  }

  const save = async () => {
    setCommandError(null)
    let input: PolicyZoneInput
    try {
      input = draftInput(draft)
    } catch (error: unknown) {
      setCommandError(
        error instanceof Error
          ? error.message
          : "Проверьте параметры исключения."
      )
      return
    }
    setBusy(true)
    try {
      const saved =
        draft.id && draft.version
          ? await planningApi.updatePolicyZone(
              token,
              warehouse.warehouse_id,
              draft.id,
              input,
              draft.version
            )
          : await planningApi.createPolicyZone(
              token,
              warehouse.warehouse_id,
              input,
              draft.createIntentKey
            )
      const authoritative = await planningApi.listPolicyZones(
        token,
        warehouse.warehouse_id
      )
      setZones(authoritative)
      setDraft(
        draftFromZone(
          authoritative.find((zone) => zone.id === saved.id) ?? saved
        )
      )
    } catch (error: unknown) {
      setCommandError(
        error instanceof Error
          ? error.message
          : "Не удалось сохранить исключение."
      )
    } finally {
      setBusy(false)
    }
  }

  const remove = async () => {
    if (!draft.id || !draft.version) return
    setCommandError(null)
    setBusy(true)
    try {
      await planningApi.deletePolicyZone(
        token,
        warehouse.warehouse_id,
        draft.id,
        draft.version
      )
      setZones(await planningApi.listPolicyZones(token, warehouse.warehouse_id))
      setDraft(emptyDraft())
      setDeleteCandidate(null)
    } catch (error: unknown) {
      setCommandError(
        error instanceof Error
          ? error.message
          : "Не удалось удалить исключение."
      )
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Исключения на карте</CardTitle>
        <CardDescription>
          Особая цена, запрет обслуживания и маршрут без прицепа для склада «
          {warehouse.name}».
        </CardDescription>
      </CardHeader>
      <CardContent className="flex min-w-0 flex-col gap-5">
        <Alert>
          <AlertTitle>Обычная доставка остаётся изохронной</AlertTitle>
          <AlertDescription>
            Последняя изохрона ограничивает дальность. Исключения действуют
            только внутри этого предела.
          </AlertDescription>
        </Alert>
        <div className="flex flex-wrap gap-2">
          <Button
            type="button"
            disabled={busy || loading}
            onClick={startCreate}
          >
            Новое исключение
          </Button>
          <Button
            type="button"
            variant="outline"
            disabled={busy || loading}
            onClick={() => {
              setLoading(true)
              setLoadError(null)
              void loadZones()
                .then(setZones)
                .catch(setLoadError)
                .finally(() => setLoading(false))
              startCreate()
            }}
          >
            Обновить исключения
          </Button>
        </div>
        {loadError ? (
          <Alert variant="destructive">
            <AlertTitle>Исключения недоступны</AlertTitle>
            <AlertDescription>
              {loadError instanceof Error
                ? loadError.message
                : "Не удалось загрузить исключения"}
            </AlertDescription>
          </Alert>
        ) : null}
        {loading ? (
          <p role="status" className="text-sm text-muted-foreground">
            Загружаем исключения…
          </p>
        ) : null}
        {!loadError && !loading ? (
          <div className="grid min-w-0 gap-5 xl:grid-cols-[14rem_minmax(0,1fr)]">
            <aside
              aria-label="Исключения склада"
              className="flex min-w-0 flex-col gap-2"
            >
              {!zones.length ? (
                <p className="text-sm text-muted-foreground">
                  Исключений пока нет. Действуют обычные тарифы и ограничения
                  дальности.
                </p>
              ) : null}
              {zones.map((zone) => (
                <Button
                  key={zone.id}
                  type="button"
                  variant={draft.id === zone.id ? "secondary" : "outline"}
                  className="h-auto w-full justify-start"
                  disabled={busy}
                  aria-pressed={draft.id === zone.id}
                  onClick={() => selectZone(zone)}
                >
                  <span
                    className="size-2 shrink-0 rounded-full"
                    style={{ background: zone.color }}
                  />
                  <span className="flex min-w-0 flex-col items-start gap-1">
                    <span className="max-w-full truncate">{zone.name}</span>
                    <span className="text-xs">{KIND_LABELS[zone.kind]}</span>
                  </span>
                </Button>
              ))}
            </aside>
            <fieldset disabled={busy} className="flex min-w-0 flex-col gap-4">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <h3 className="font-semibold">
                  {selected ? selected.name : "Новое исключение"}
                </h3>
                {selected ? (
                  <Badge variant="outline">{KIND_LABELS[selected.kind]}</Badge>
                ) : null}
              </div>
              <Separator />
              <FieldGroup className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                <Field>
                  <FieldLabel htmlFor="policy-name">Название</FieldLabel>
                  <Input
                    id="policy-name"
                    value={draft.name}
                    maxLength={200}
                    onChange={(event) =>
                      setDraft((current) => ({
                        ...current,
                        name: event.target.value,
                      }))
                    }
                  />
                </Field>
                <Field>
                  <FieldLabel htmlFor="policy-kind">Правило</FieldLabel>
                  <Select
                    value={draft.kind}
                    disabled={busy}
                    onValueChange={(value) =>
                      changeKind(value as PolicyZoneKind)
                    }
                  >
                    <SelectTrigger id="policy-kind" className="w-full">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectGroup>
                        <SelectItem value="SPECIAL_PRICE">
                          Особая цена
                        </SelectItem>
                        <SelectItem value="FORBIDDEN">Запрещено</SelectItem>
                        <SelectItem value="NO_TRAILER">
                          Только без прицепа
                        </SelectItem>
                      </SelectGroup>
                    </SelectContent>
                  </Select>
                </Field>
                <Field>
                  <FieldLabel htmlFor="policy-color">Цвет на карте</FieldLabel>
                  <Input
                    id="policy-color"
                    type="color"
                    value={draft.color}
                    onChange={(event) =>
                      setDraft((current) => ({
                        ...current,
                        color: event.target.value.toUpperCase(),
                      }))
                    }
                  />
                </Field>
              </FieldGroup>
              {draft.kind === "SPECIAL_PRICE" ? (
                <FieldGroup className="grid grid-cols-1 gap-4 sm:grid-cols-2">
                  <Field>
                    <FieldLabel htmlFor="policy-delivery">
                      Доставка, ₽
                    </FieldLabel>
                    <Input
                      id="policy-delivery"
                      type="number"
                      min="0"
                      step="1"
                      value={draft.deliveryPriceRubles}
                      onChange={(event) =>
                        setDraft((current) => ({
                          ...current,
                          deliveryPriceRubles: event.target.value,
                        }))
                      }
                    />
                  </Field>
                  <Field>
                    <FieldLabel htmlFor="policy-pickup">Вывоз, ₽</FieldLabel>
                    <Input
                      id="policy-pickup"
                      type="number"
                      min="0"
                      step="1"
                      value={draft.pickupPriceRubles}
                      onChange={(event) =>
                        setDraft((current) => ({
                          ...current,
                          pickupPriceRubles: event.target.value,
                        }))
                      }
                    />
                  </Field>
                </FieldGroup>
              ) : (
                <p className="text-sm text-muted-foreground">
                  {draft.kind === "FORBIDDEN"
                    ? "Точки внутри контура недоступны для новых слотов и планирования."
                    : "Точки внутри контура планируются только без прицепа."}
                </p>
              )}
              <PolicyZoneMapEditor
                warehouse={warehouse}
                zones={zones}
                selectedZoneId={draft.id}
                geometry={draft.geometry}
                color={draft.color}
                disabled={busy}
                onGeometryChange={(geometry) =>
                  setDraft((current) => ({
                    ...current,
                    geometry,
                    geometryText: policyZoneGeometryText(geometry),
                  }))
                }
              />
              <details>
                <summary className="cursor-pointer text-sm font-medium">
                  Точные координаты контура
                </summary>
                <Field className="mt-3">
                  <FieldLabel htmlFor="policy-geometry">
                    GeoJSON MultiPolygon
                  </FieldLabel>
                  <Textarea
                    id="policy-geometry"
                    spellCheck={false}
                    value={draft.geometryText}
                    onChange={(event) => changeGeometryText(event.target.value)}
                    className="min-h-36"
                  />
                  <FieldDescription>
                    Координаты: [долгота, широта]. Можно вставить готовый
                    контур; сервер проверит замыкание и самопересечения.
                  </FieldDescription>
                </Field>
              </details>
              {commandError ? (
                <Alert variant="destructive">
                  <AlertDescription>{commandError}</AlertDescription>
                </Alert>
              ) : null}
              <Separator />
              <div className="flex flex-wrap items-center justify-between gap-3">
                {selected && deleteCandidate !== selected.id ? (
                  <Button
                    type="button"
                    variant="destructive"
                    onClick={() => setDeleteCandidate(selected.id)}
                  >
                    Удалить исключение
                  </Button>
                ) : (
                  <span />
                )}
                {selected && deleteCandidate === selected.id ? (
                  <Alert>
                    <AlertDescription>
                      Удалить «{selected.name}»?
                      <div className="flex gap-2">
                        <Button
                          type="button"
                          variant="outline"
                          onClick={() => setDeleteCandidate(null)}
                        >
                          Отмена
                        </Button>
                        <Button
                          type="button"
                          variant="destructive"
                          onClick={() => void remove()}
                        >
                          Да, удалить
                        </Button>
                      </div>
                    </AlertDescription>
                  </Alert>
                ) : null}
                <Button type="button" onClick={() => void save()}>
                  {busy
                    ? "Сохраняем…"
                    : selected
                      ? "Сохранить изменения"
                      : "Создать исключение"}
                </Button>
              </div>
            </fieldset>
          </div>
        ) : null}
      </CardContent>
    </Card>
  )
}

function emptyDraft(): PolicyZoneDraft {
  return {
    id: null,
    version: null,
    name: "",
    kind: "SPECIAL_PRICE",
    color: KIND_COLORS.SPECIAL_PRICE,
    geometryText: "",
    geometry: null,
    deliveryPriceRubles: "",
    pickupPriceRubles: "",
    createIntentKey: crypto.randomUUID(),
  }
}

function draftFromZone(zone: WarehousePolicyZone): PolicyZoneDraft {
  return {
    id: zone.id,
    version: zone.version,
    name: zone.name,
    kind: zone.kind,
    color: zone.color,
    geometryText: policyZoneGeometryText(zone.geometry),
    geometry: zone.geometry,
    deliveryPriceRubles: zone.delivery_price_rubles?.toString() ?? "",
    pickupPriceRubles: zone.pickup_price_rubles?.toString() ?? "",
    createIntentKey: crypto.randomUUID(),
  }
}

function draftInput(draft: PolicyZoneDraft): PolicyZoneInput {
  const name = draft.name.trim()
  if (!name) throw new Error("Укажите название исключения.")
  if (!/^#[0-9A-F]{6}$/u.test(draft.color))
    throw new Error("Укажите цвет в формате #RRGGBB.")
  const geometry = parsePolicyZoneGeometry(draft.geometryText)
  if (draft.kind !== "SPECIAL_PRICE") {
    return {
      name,
      kind: draft.kind,
      color: draft.color,
      geometry,
      delivery_price_rubles: null,
      pickup_price_rubles: null,
    }
  }
  return {
    name,
    kind: draft.kind,
    color: draft.color,
    geometry,
    delivery_price_rubles: parseRubles(draft.deliveryPriceRubles, "доставки"),
    pickup_price_rubles: parseRubles(draft.pickupPriceRubles, "вывоза"),
  }
}

function parseRubles(value: string, label: string): number {
  if (!/^\d+$/u.test(value))
    throw new Error(`Укажите целую неотрицательную цену ${label}.`)
  const parsed = Number(value)
  if (!Number.isSafeInteger(parsed))
    throw new Error(`Цена ${label} слишком велика.`)
  return parsed
}
