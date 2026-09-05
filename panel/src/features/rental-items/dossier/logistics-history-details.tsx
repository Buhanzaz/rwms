import {
  useInfiniteQuery,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query"
import { getEquipmentItems } from "@/api/equipment-api"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Skeleton } from "@/components/ui/skeleton"
import { useAuth } from "@/features/auth/use-auth"
import {
  getLogisticsDocumentHistory,
  type HistoryEquipment,
  type LogisticsHistoryEvent,
  type LogisticsHistoryReference,
} from "@/features/logistics/api/document-history-api"
import { logisticsStatusVariant } from "@/features/logistics/logistics-status-variant"
import { RETURNS_QUERY_KEY } from "@/features/logistics/returns/api"
import { RETURN_STATE_LABELS } from "@/features/logistics/returns/model"
import { SHIPMENTS_QUERY_KEY } from "@/features/logistics/shipments/api"
import { SHIPMENT_STATE_LABELS } from "@/features/logistics/shipments/model"
import { formatDossierActorDisplay } from "./actor/actor-display"
import { useDossierActorDisplays } from "./actor/use-dossier-actor-displays"

const eventLabels: Record<string, string> = {
  "logistics.return.created.v1": "Создан возврат",
  "logistics.return.registration-started.v1": "Начата регистрация возврата",
  "logistics.return.inspection-required.v1":
    "Возврат зарегистрирован на складе · требуется осмотр",
  "logistics.return.acceptance-started.v1":
    "Подтверждена приёмка без повреждений",
  "logistics.return.accepted.v1": "Приёмка завершена системой",
  "logistics.return.estimate-started.v1":
    "Переданы фотографии для составления сметы",
  "logistics.return.estimate-requested.v1": "Запрос сметы зарегистрирован",
  "logistics.return.conflict.v1": "Обнаружен конфликт возврата",
  "logistics.return.reconciliation-required.v1": "Возврат направлен на сверку",
  "logistics.shipment.created.v1": "Создана отгрузка",
  "logistics.shipment.draft-updated.v1": "Изменён черновик отгрузки",
  "logistics.shipment.preparation-started.v1": "Начата подготовка отгрузки",
  "logistics.shipment.planned.v1": "Отгрузка запланирована",
  "logistics.shipment.confirmation-started.v1":
    "Подтверждена подготовка отгрузки",
  "logistics.shipment.preparation-confirmed.v1":
    "Оформление отгрузки завершено",
  "logistics.shipment.cancellation-started.v1": "Начата отмена отгрузки",
  "logistics.shipment.cancelled.v1": "Отгрузка отменена",
  "logistics.shipment.conflict.v1": "Обнаружен конфликт отгрузки",
  "logistics.shipment.reconciliation-required.v1":
    "Отгрузка направлена на сверку",
}
// Only these owner events prove the actual command actor. Automatic completion retains the creator.
const commandActors: Record<string, string> = {
  "logistics.return.created.v1": "Автор документа",
  "logistics.shipment.created.v1": "Автор документа",
  "logistics.return.acceptance-started.v1": "Приёмку подтвердил",
  "logistics.return.estimate-started.v1": "Осмотр передал на смету",
  "logistics.shipment.confirmation-started.v1": "Подготовку подтвердил",
}
const stateLabels: Record<string, string> = {
  ...RETURN_STATE_LABELS,
  ...SHIPMENT_STATE_LABELS,
}

function instant(value: string) {
  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value))
}

function EquipmentEvidence({
  title,
  rows,
  names,
  description,
}: {
  title: string
  rows: HistoryEquipment[] | null
  names: ReadonlyMap<string, string>
  description: string
}) {
  return (
    <section aria-label={title} className="flex min-w-0 flex-col gap-2">
      <h4 className="font-medium">{title}</h4>
      <p className="text-xs text-muted-foreground">{description}</p>
      {rows === null ? (
        <p>Состав не зафиксирован.</p>
      ) : rows.length === 0 ? (
        <p>Зафиксирован пустой состав.</p>
      ) : (
        <dl className="flex flex-col gap-2">
          {rows.map((row) => (
            <div
              key={row.equipmentId}
              className="flex items-start justify-between gap-3"
            >
              <dt className="min-w-0 break-words">
                {names.get(row.equipmentId) ?? (
                  <span className="break-all">
                    Оборудование {row.equipmentId}
                  </span>
                )}
              </dt>
              <dd className="shrink-0 tabular-nums">{row.quantity} шт.</dd>
            </div>
          ))}
        </dl>
      )}
    </section>
  )
}

/** Lazy owner history: immutable quantities and exact command actors, never inferred from current contents. */
export function LogisticsHistoryDetails({
  reference,
  cabinId,
  documentVersion,
}: {
  reference: LogisticsHistoryReference
  cabinId: string
  documentVersion: number
}) {
  const { accessToken, currentUser } = useAuth()
  const client = useQueryClient()
  const queryKey = [
    ...(reference.documentType === "RETURN"
      ? RETURNS_QUERY_KEY
      : SHIPMENTS_QUERY_KEY),
    "document-history",
    currentUser?.id,
    reference.warehouseId,
    reference.documentId,
    cabinId,
    documentVersion,
  ]
  const query = useInfiniteQuery({
    queryKey,
    queryFn: ({ pageParam, signal }) =>
      getLogisticsDocumentHistory(accessToken!, reference, pageParam, signal),
    initialPageParam: -1,
    getNextPageParam: (page) => page.nextAfterVersion ?? undefined,
    enabled: Boolean(accessToken && currentUser),
    staleTime: 30_000,
    retry: false,
  })
  const firstPage = query.data?.pages[0]
  const versionChanged = query.data?.pages.some(
    (page) => page.documentVersion !== firstPage?.documentVersion
  )
  const line = firstPage?.lines.find((row) => row.assetId === cabinId)
  const events = query.data?.pages.flatMap((page) => page.events) ?? []
  const actors = useDossierActorDisplays(
    events.flatMap((event) =>
      !event.baseline &&
      commandActors[event.eventType] &&
      event.recordedActor?.principalType === "USER"
        ? [event.recordedActor.subjectId]
        : []
    )
  )
  const hasEquipment = Boolean(
    line &&
    [
      ...(line.contentsBeforeOperation?.contents ?? []),
      ...(line.contentsAfterRegistration?.contents ?? []),
      ...(line.returnAcceptance?.additionalEquipment ?? []),
      ...(line.inventoryShipmentFurniture ?? []),
    ].length
  )
  const equipment = useQuery({
    queryKey: [
      "equipment-items",
      "dossier-names",
      currentUser?.id,
      reference.warehouseId,
    ],
    queryFn: () =>
      getEquipmentItems(accessToken!, { warehouseId: reference.warehouseId }),
    enabled: Boolean(accessToken && currentUser && hasEquipment),
    staleTime: 60_000,
    retry: false,
  })
  const names = new Map(equipment.data?.map((item) => [item.id, item.name]))
  const actorName = (event: LogisticsHistoryEvent) => {
    if (!event.recordedActor) return "Участник не зафиксирован"
    if (event.recordedActor.principalType !== "USER") return "Сервис"
    const display = actors.get(event.recordedActor.subjectId)
    return display
      ? formatDossierActorDisplay(display)
      : `Имя недоступно · ${event.recordedActor.subjectId}`
  }
  if (!accessToken || !currentUser)
    return (
      <Alert variant="destructive">
        <AlertDescription>
          Для просмотра истории требуется авторизация.
        </AlertDescription>
      </Alert>
    )
  if (query.isPending)
    return (
      <Skeleton
        className="h-24 w-full"
        aria-label="Загрузка состава и журнала документа"
      />
    )
  if (versionChanged || (firstPage && !line))
    return (
      <Alert variant="destructive">
        <AlertTitle>История требует обновления</AlertTitle>
        <AlertDescription>
          {versionChanged
            ? "Документ изменился во время загрузки. Обновите историю целиком."
            : "В истории документа отсутствует выбранная бытовка."}
        </AlertDescription>
        <Button
          variant="outline"
          size="sm"
          className="mt-2 w-fit"
          onClick={() => void client.resetQueries({ queryKey, exact: true })}
        >
          Обновить историю
        </Button>
      </Alert>
    )
  return (
    <section
      aria-label="Комплектация и журнал документа"
      className="flex min-w-0 flex-col gap-4 text-sm"
    >
      {query.error ? (
        <Alert variant="destructive">
          <AlertTitle>Не удалось загрузить подробную историю</AlertTitle>
          <AlertDescription>{query.error.message}</AlertDescription>
          <Button
            variant="outline"
            size="sm"
            className="mt-2 w-fit"
            disabled={query.isFetching}
            onClick={() =>
              void (query.isFetchNextPageError
                ? query.fetchNextPage()
                : query.refetch())
            }
          >
            Повторить загрузку истории
          </Button>
        </Alert>
      ) : null}
      {equipment.error ? (
        <Alert>
          <AlertTitle>Названия оборудования недоступны</AlertTitle>
          <AlertDescription>
            {equipment.error.message} Сохранённые количества показаны по
            идентификаторам.
          </AlertDescription>
          <Button
            variant="outline"
            size="sm"
            className="mt-2 w-fit"
            onClick={() => void equipment.refetch()}
          >
            Повторить загрузку названий
          </Button>
        </Alert>
      ) : null}
      {line ? (
        <>
          <div className="grid gap-4 md:grid-cols-2">
            <EquipmentEvidence
              title={
                reference.documentType === "RETURN"
                  ? "Состав до регистрации возврата"
                  : "Состав перед подготовкой отгрузки"
              }
              rows={line.contentsBeforeOperation?.contents ?? null}
              names={names}
              description={
                reference.documentType === "RETURN"
                  ? "Сохранённые учётные данные до поступления бытовки."
                  : "Это исходная комплектация, не окончательная отгрузочная накладная."
              }
            />
            {reference.documentType === "RETURN" ? (
              <EquipmentEvidence
                title="Состав после регистрации возврата"
                rows={line.contentsAfterRegistration?.contents ?? null}
                names={names}
                description="Учётный состав после поступления. Не заменяет физический осмотр."
              />
            ) : null}
            {line.inventoryShipmentFurniture !== null ? (
              <EquipmentEvidence
                title="Состав исторической отгрузки"
                rows={line.inventoryShipmentFurniture}
                names={names}
                description="Сохранён при подтверждении результатов инвентаризации."
              />
            ) : null}
          </div>
          {reference.documentType === "RETURN" ? (
            <section
              aria-label="Подтверждение комплектации"
              className="flex flex-col gap-2"
            >
              <h4 className="font-medium">Подтверждение комплектации</h4>
              {line.returnAcceptance ? (
                <>
                  <p>
                    <Badge variant="info">Комплектность подтверждена</Badge> при
                    направлении на приёмку без повреждений.
                  </p>
                  <p className="text-xs text-muted-foreground">
                    Подтверждение сотрудника не означает завершение всех этапов
                    приёмки.
                  </p>
                  {line.returnAcceptance.additionalEquipment.length ? (
                    <EquipmentEvidence
                      title="Дополнительно принятое оборудование"
                      rows={line.returnAcceptance.additionalEquipment}
                      names={names}
                      description="Допоборудование, указанное сотрудником в подтверждении приёмки."
                    />
                  ) : (
                    <p>Допоборудование при подтверждении не заявлено.</p>
                  )}
                </>
              ) : (
                <p>
                  Подтверждение комплектности не зафиксировано. Это не означает
                  отсутствие или недостачу мебели.
                </p>
              )}
            </section>
          ) : null}
          {hasEquipment ? (
            <p className="text-xs text-muted-foreground">
              Количество — из сохранённых документов; названия — из текущего
              справочника.
            </p>
          ) : null}
        </>
      ) : null}
      {firstPage ? (
        <section aria-label="Журнал этапов" className="flex flex-col gap-3">
          <h4 className="font-medium">Журнал этапов</h4>
          <p className="text-xs text-muted-foreground">
            Сотрудник указан для подтверждённых пользовательских действий.
            Автоматическое завершение не приписывается автору документа.
          </p>
          {events.length === 0 ? (
            <p>События документа не зафиксированы.</p>
          ) : (
            <ol className="flex flex-col gap-3">
              {events.map((event) => (
                <li key={event.eventId} className="flex flex-col gap-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-medium">
                      {event.baseline
                        ? "Исходная историческая запись"
                        : (eventLabels[event.eventType] ?? "Событие документа")}
                    </span>
                    <Badge variant={logisticsStatusVariant(event.state)}>
                      {stateLabels[event.state]}
                    </Badge>
                    <span className="text-xs text-muted-foreground">
                      {event.occurredAt
                        ? instant(event.occurredAt)
                        : `Время операции не зафиксировано · запись ${instant(event.recordedAt)}`}
                    </span>
                  </div>
                  {!event.baseline && commandActors[event.eventType] ? (
                    <p>
                      {commandActors[event.eventType]}: {actorName(event)}
                    </p>
                  ) : null}
                  {!eventLabels[event.eventType] && !event.baseline ? (
                    <p className="text-xs break-all text-muted-foreground">
                      {event.eventType}
                    </p>
                  ) : null}
                </li>
              ))}
            </ol>
          )}
          {query.hasNextPage ? (
            <div className="flex flex-wrap items-center gap-2">
              <Button
                size="sm"
                variant="outline"
                disabled={query.isFetching}
                onClick={() => void query.fetchNextPage()}
              >
                Загрузить следующие события
              </Button>
              <span className="text-xs text-muted-foreground">
                Показана часть журнала: {events.length} событий.
              </span>
            </div>
          ) : null}
        </section>
      ) : null}
    </section>
  )
}
