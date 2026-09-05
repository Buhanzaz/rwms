import { lazy, Suspense, useState } from "react"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
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
import { Skeleton } from "@/components/ui/skeleton"
import { Switch } from "@/components/ui/switch"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { useAuth } from "@/features/auth/use-auth"
import { useWarehouse } from "@/hooks/use-warehouse"
import { planningApi } from "./planning-api"
import {
  operationFields,
  optimizationFields,
  routingFields,
  type NumericSetting,
} from "./planning-fields"
import type {
  PlannerWarehouseSettings,
  PlanningSettings,
  PlanningSettingsInput,
} from "./planning-types"

const PolicyZoneManager = lazy(() => import("./policy-zone-manager"))

/** One warehouse configuration in the isolated administration application. */
export function PlanningSettingsPage() {
  const { accessToken, currentUser } = useAuth()
  const {
    warehouses,
    selectedWarehouseId,
    setSelectedWarehouseId,
    isLoading,
    error,
    reloadWarehouses,
  } = useWarehouse()
  const client = useQueryClient()
  const queryKey = [
    "admin-planning-settings",
    currentUser?.id,
    selectedWarehouseId,
  ]
  const query = useQuery({
    queryKey,
    queryFn: ({ signal }) =>
      planningApi.getSettings(accessToken!, selectedWarehouseId!, signal),
    enabled: !!accessToken && !!selectedWarehouseId,
    retry: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })
  return (
    <div className="flex min-w-0 flex-col gap-5">
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div className="flex flex-col gap-1">
          <h1 className="text-xl font-semibold">Настройки логистики</h1>
          <p className="text-sm text-muted-foreground">
            Правила планирования, стоимость доставки и исключения для выбранного
            склада.
          </p>
        </div>
        <Field className="w-full sm:w-72">
          <FieldLabel htmlFor="planner-warehouse">Склад</FieldLabel>
          <Select
            value={selectedWarehouseId ?? ""}
            onValueChange={setSelectedWarehouseId}
            disabled={isLoading}
          >
            <SelectTrigger id="planner-warehouse" className="w-full">
              <SelectValue placeholder="Выберите склад" />
            </SelectTrigger>
            <SelectContent>
              <SelectGroup>
                {warehouses
                  .filter((w) => w.active && w.lifecycleState === "ACTIVE")
                  .map((warehouse) => (
                    <SelectItem key={warehouse.id} value={warehouse.id}>
                      {warehouse.name}
                    </SelectItem>
                  ))}
              </SelectGroup>
            </SelectContent>
          </Select>
        </Field>
      </header>
      <Separator />
      {error ? (
        <Alert variant="destructive">
          <AlertTitle>Список складов недоступен</AlertTitle>
          <AlertDescription>
            {error}
            <Button variant="outline" onClick={() => void reloadWarehouses()}>
              Повторить
            </Button>
          </AlertDescription>
        </Alert>
      ) : null}
      {!accessToken ? (
        <Alert variant="destructive">
          <AlertDescription>
            Для настроек требуется авторизация администратора.
          </AlertDescription>
        </Alert>
      ) : selectedWarehouseId ? (
        <>
          {query.isPending ? <Skeleton className="h-64 w-full" /> : null}
          {query.isError ? (
            <Alert variant="destructive">
              <AlertTitle>Настройки недоступны</AlertTitle>
              <AlertDescription>
                {query.error.message}
                <Button variant="outline" onClick={() => void query.refetch()}>
                  Повторить загрузку
                </Button>
              </AlertDescription>
            </Alert>
          ) : null}
          {query.data && !query.isError ? (
            <PlanningSettingsForm
              key={`${currentUser?.id}:${selectedWarehouseId}:${query.dataUpdatedAt}`}
              warehouse={query.data}
              token={accessToken}
              onReload={async () => {
                await query.refetch()
              }}
              onSave={async (input, version) => {
                const saved = await planningApi.saveSettings(
                  accessToken,
                  selectedWarehouseId,
                  input,
                  version
                )
                client.setQueryData(queryKey, saved)
                toast.success("Настройки сохранены")
              }}
            />
          ) : null}
        </>
      ) : (
        <p className="text-sm text-muted-foreground">
          Выберите склад, чтобы открыть его настройки.
        </p>
      )}
    </div>
  )
}

function SettingGroup({
  title,
  fields,
  settings,
  onNumber,
}: {
  title: string
  fields: NumericSetting[]
  settings: PlanningSettings
  onNumber: (key: NumericSetting["key"], value: number) => void
}) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>{title}</CardTitle>
      </CardHeader>
      <CardContent>
        <FieldGroup className="grid grid-cols-1 gap-5 md:grid-cols-2 xl:grid-cols-3">
          {fields.map((field) => (
            <Field key={field.key}>
              <FieldLabel htmlFor={`planner-${field.key}`}>
                {field.label}
              </FieldLabel>
              <Input
                id={`planner-${field.key}`}
                type="number"
                required
                step={field.step ?? "1"}
                min={field.min}
                max={field.max}
                value={settings[field.key]}
                onChange={(event) =>
                  onNumber(field.key, Number(event.target.value))
                }
              />
              {field.hint ? (
                <FieldDescription>{field.hint}</FieldDescription>
              ) : null}
            </Field>
          ))}
        </FieldGroup>
      </CardContent>
    </Card>
  )
}

/** Keep the observed version and all unseen settings intact until explicit save or reload. */
export function PlanningSettingsForm({
  warehouse,
  token,
  onSave,
  onReload,
}: {
  warehouse: PlannerWarehouseSettings
  token: string
  onSave: (input: PlanningSettingsInput, version: number) => Promise<void>
  onReload: () => Promise<void>
}) {
  const [tab, setTab] = useState("algorithm")
  const [draft, setDraft] = useState(warehouse.settings)
  const [tariffs, setTariffs] = useState(warehouse.isochrone_tariffs)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const onNumber = (key: NumericSetting["key"], value: number) =>
    setDraft((current) => ({ ...current, [key]: value }))
  const save = async () => {
    setBusy(true)
    setError(null)
    try {
      await onSave(
        { settings: draft, isochrone_tariffs: tariffs },
        warehouse.version
      )
    } catch (cause) {
      setError(
        cause instanceof Error
          ? cause.message
          : "Не удалось сохранить настройки"
      )
    } finally {
      setBusy(false)
    }
  }
  return (
    <div className="flex min-w-0 flex-col gap-4">
      {["FAILED", "PENDING"].includes(warehouse.capacity_publish_status) ? (
        <Alert>
          <AlertTitle>Доступные слоты ещё обновляются</AlertTitle>
          <AlertDescription>
            Настройки сохранены.{" "}
            {warehouse.capacity_publish_status === "FAILED"
              ? "Сервис не смог опубликовать новую доступность; требуется повторная публикация сервисом."
              : "Публикация доступности ещё не завершена."}
          </AlertDescription>
        </Alert>
      ) : null}
      <Tabs value={tab} onValueChange={setTab}>
        <TabsList className="w-full sm:w-fit">
          <TabsTrigger value="algorithm">Алгоритм</TabsTrigger>
          <TabsTrigger value="tariffs">Тарифы</TabsTrigger>
          <TabsTrigger value="policies">Исключения</TabsTrigger>
        </TabsList>
        <form
          hidden={tab === "policies"}
          onSubmit={(event) => {
            event.preventDefault()
            void save()
          }}
        >
          <fieldset disabled={busy} className="min-w-0">
            <TabsContent value="algorithm" className="flex flex-col gap-4">
              <SettingGroup
                title="Время и состав операций"
                fields={operationFields}
                settings={draft}
                onNumber={onNumber}
              />
              <Card>
                <CardHeader>
                  <CardTitle>Продолжительность смены</CardTitle>
                  <CardDescription>
                    Дополнительные рейсы после обычного окончания смены.
                  </CardDescription>
                </CardHeader>
                <CardContent>
                  <FieldGroup>
                    <Field orientation="horizontal">
                      <Switch
                        id="planner-overtime"
                        checked={draft.allow_soft_overtime}
                        onCheckedChange={(value) =>
                          setDraft((current) => ({
                            ...current,
                            allow_soft_overtime: value,
                          }))
                        }
                      />
                      <FieldLabel htmlFor="planner-overtime">
                        Разрешить переработку
                      </FieldLabel>
                    </Field>
                    <Field className="max-w-sm">
                      <FieldLabel htmlFor="planner-overtime-limit">
                        Максимальная переработка, ч
                      </FieldLabel>
                      <Input
                        id="planner-overtime-limit"
                        type="number"
                        required
                        step="0.25"
                        min="0"
                        max="12"
                        disabled={!draft.allow_soft_overtime}
                        value={draft.soft_overtime_limit_minutes / 60}
                        onChange={(event) =>
                          onNumber(
                            "soft_overtime_limit_minutes",
                            Math.round(Number(event.target.value) * 60)
                          )
                        }
                      />
                      <FieldDescription>
                        Предел учитывается в маршрутах и клиентских слотах,
                        внутри календарного дня.
                      </FieldDescription>
                    </Field>
                    <Field orientation="horizontal">
                      <Checkbox id="planner-delivery-first" checked disabled />
                      <FieldLabel htmlFor="planner-delivery-first">
                        В каждом цикле доставки раньше вывозов
                      </FieldLabel>
                    </Field>
                  </FieldGroup>
                </CardContent>
              </Card>
              <SettingGroup
                title="Расчёт маршрута"
                fields={routingFields}
                settings={draft}
                onNumber={onNumber}
              />
              <SettingGroup
                title="Оптимизация загрузки"
                fields={optimizationFields}
                settings={draft}
                onNumber={onNumber}
              />
            </TabsContent>
            <TabsContent value="tariffs">
              <Card>
                <CardHeader>
                  <CardTitle>Стоимость доставки по изохронам</CardTitle>
                  <CardDescription>
                    Цена определяется временем пути от склада. Последняя ступень
                    ограничивает приём новых заказов.
                  </CardDescription>
                </CardHeader>
                <CardContent className="flex flex-col gap-5">
                  <FieldGroup className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
                    {tariffs.map((tariff, index) => (
                      <Field key={tariff.travel_minutes}>
                        <FieldLabel htmlFor={`planner-tariff-${index}`}>
                          До {index + 1} ч, ₽
                        </FieldLabel>
                        <Input
                          id={`planner-tariff-${index}`}
                          type="number"
                          required
                          min="0"
                          step="1"
                          value={tariff.price_rubles}
                          onChange={(event) =>
                            setTariffs((current) =>
                              current.map((item, i) =>
                                i === index
                                  ? {
                                      ...item,
                                      price_rubles: Number(event.target.value),
                                    }
                                  : item
                              )
                            )
                          }
                        />
                      </Field>
                    ))}
                  </FieldGroup>
                  <Alert>
                    <AlertDescription>
                      Заказы дальше {tariffs.length} ч от склада недоступны.
                    </AlertDescription>
                  </Alert>
                  <div className="flex flex-wrap gap-2">
                    <Button
                      type="button"
                      variant="outline"
                      disabled={tariffs.length >= 12}
                      onClick={() =>
                        setTariffs((current) => [
                          ...current,
                          {
                            travel_minutes: (current.length + 1) * 60,
                            price_rubles: current.at(-1)!.price_rubles,
                          },
                        ])
                      }
                    >
                      Добавить {tariffs.length + 1}-й час
                    </Button>
                    <Button
                      type="button"
                      variant="outline"
                      disabled={tariffs.length <= 1}
                      onClick={() =>
                        setTariffs((current) => current.slice(0, -1))
                      }
                    >
                      Удалить последнюю ступень
                    </Button>
                  </div>
                </CardContent>
              </Card>
            </TabsContent>
          </fieldset>
          <div className="mt-5 flex flex-col gap-4">
            <Separator />
            {error ? (
              <Alert variant="destructive">
                <AlertTitle>Настройки не сохранены</AlertTitle>
                <AlertDescription>{error}</AlertDescription>
              </Alert>
            ) : null}
            <div className="flex flex-wrap items-center justify-between gap-3">
              <p className="text-sm text-muted-foreground">
                Алгоритм и тарифы сохраняются вместе. Исключения сохраняются
                отдельно.
              </p>
              <div className="flex flex-wrap gap-2">
                <Button
                  type="button"
                  variant="outline"
                  disabled={busy}
                  onClick={() => void onReload()}
                >
                  Загрузить сохранённые настройки
                </Button>
                <Button type="submit" disabled={busy}>
                  {busy ? "Сохраняем…" : "Сохранить настройки"}
                </Button>
              </div>
            </div>
          </div>
        </form>
        <TabsContent value="policies">
          <Suspense fallback={<Skeleton className="h-64 w-full" />}>
            <PolicyZoneManager warehouse={warehouse} token={token} />
          </Suspense>
        </TabsContent>
      </Tabs>
    </div>
  )
}
