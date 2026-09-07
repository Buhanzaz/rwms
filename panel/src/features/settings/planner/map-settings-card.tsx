import { useState } from "react"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
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
import { Skeleton } from "@/components/ui/skeleton"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import { useAuth } from "@/features/auth/use-auth"
import { ApiError } from "@/lib/api-client"
import {
  mapSettingsApi,
  notifyMapSettingsChanged,
  type AdminMapSettings,
  type MapProvider,
  type MapSettingsInput,
} from "./map-settings-api"

/** A global display preference, available even when no warehouse is selected. */
export function MapSettingsCard() {
  const { accessToken, currentUser } = useAuth()
  const client = useQueryClient()
  const queryKey = ["admin-map-settings", currentUser?.id]
  const query = useQuery({
    queryKey,
    queryFn: ({ signal }) => mapSettingsApi.get(accessToken!, signal),
    enabled: !!accessToken,
    retry: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })

  if (!accessToken) return null
  if (query.isPending) return <Skeleton className="h-64 w-full" />
  if (query.isError || !query.data) {
    return (
      <Alert variant="destructive">
        <AlertTitle>Настройки карты недоступны</AlertTitle>
        <AlertDescription>
          {query.error?.message}
          <Button variant="outline" onClick={() => void query.refetch()}>
            Повторить загрузку карты
          </Button>
        </AlertDescription>
      </Alert>
    )
  }

  return (
    <MapSettingsForm
      key={`${currentUser?.id}:${query.data.version}`}
      settings={query.data}
      onReload={async () => {
        await query.refetch()
      }}
      onSave={async (input) => {
        const saved = await mapSettingsApi.save(accessToken, input)
        client.setQueryData(queryKey, saved)
        notifyMapSettingsChanged(saved.version)
        toast.success("Карта для логистики сохранена")
      }}
    />
  )
}

function MapSettingsForm({
  settings,
  onSave,
  onReload,
}: {
  settings: AdminMapSettings
  onSave: (input: MapSettingsInput) => Promise<void>
  onReload: () => Promise<void>
}) {
  const [provider, setProvider] = useState<MapProvider>(settings.provider)
  const [apiKey, setApiKey] = useState("")
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [conflict, setConflict] = useState(false)
  const keyRequired =
    provider === "YANDEX" && !settings.yandex_api_key_configured
  const keyMissing = keyRequired && !apiKey.trim()
  const changed = provider !== settings.provider || !!apiKey.trim()

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy || keyMissing || !changed || conflict) return
    setBusy(true)
    setError(null)
    try {
      await onSave({
        expected_version: settings.version,
        provider,
        yandex_api_key: apiKey.trim() || null,
      })
      setApiKey("")
    } catch (cause) {
      const stale = cause instanceof ApiError && cause.status === 409
      setConflict(stale)
      setError(
        stale
          ? "Настройки изменил другой администратор. Загрузите актуальную версию."
          : cause instanceof Error
            ? cause.message
            : "Не удалось сохранить настройки карты."
      )
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Карта в логистике</CardTitle>
        <CardDescription>
          Общая настройка для всех складов. Сохранение переключает карту в
          открытой логистике без перезагрузки страницы.
        </CardDescription>
      </CardHeader>
      <form onSubmit={(event) => void submit(event)}>
        <CardContent>
          <FieldGroup>
            <Field>
              <FieldLabel id="map-provider-label">Поставщик карты</FieldLabel>
              <ToggleGroup
                type="single"
                value={provider}
                onValueChange={(value) => {
                  if (value === "STANDARD" || value === "YANDEX")
                    setProvider(value)
                }}
                variant="outline"
                disabled={busy || conflict}
                aria-labelledby="map-provider-label"
              >
                <ToggleGroupItem value="STANDARD">Стандартная</ToggleGroupItem>
                <ToggleGroupItem value="YANDEX">Яндекс Карты</ToggleGroupItem>
              </ToggleGroup>
              <FieldDescription>
                Стандартная — текущая карта логистики.
              </FieldDescription>
            </Field>
            <Field data-invalid={keyMissing || undefined}>
              <FieldLabel htmlFor="yandex-map-api-key">
                API-ключ Яндекс Карт
              </FieldLabel>
              <Input
                id="yandex-map-api-key"
                type="password"
                autoComplete="new-password"
                value={apiKey}
                onChange={(event) => setApiKey(event.target.value)}
                maxLength={256}
                required={keyRequired}
                aria-invalid={keyMissing || undefined}
                disabled={busy || conflict}
                placeholder={
                  settings.yandex_api_key_configured
                    ? "Ключ сохранён; введите новый для замены"
                    : "Ключ JavaScript API 3.0"
                }
                aria-describedby="yandex-map-api-key-help"
              />
              <FieldDescription id="yandex-map-api-key-help">
                {settings.yandex_api_key_configured
                  ? "Ключ сохранён. Пустое поле оставляет его без изменений. "
                  : "Для Яндекс Карт необходим ключ JavaScript API 3.0. "}
                В кабинете Яндекса разрешите домен {window.location.hostname} в
                HTTP Referer.
              </FieldDescription>
            </Field>
            {error ? (
              <Alert variant="destructive">
                <AlertDescription>
                  {error}
                  {conflict ? (
                    <Button
                      variant="outline"
                      type="button"
                      onClick={() => void onReload()}
                    >
                      Загрузить актуальные настройки карты
                    </Button>
                  ) : null}
                </AlertDescription>
              </Alert>
            ) : null}
          </FieldGroup>
        </CardContent>
        <CardFooter className="pt-4">
          <Button
            type="submit"
            disabled={busy || keyMissing || !changed || conflict}
          >
            {busy ? "Сохраняем…" : "Сохранить карту"}
          </Button>
        </CardFooter>
      </form>
    </Card>
  )
}
