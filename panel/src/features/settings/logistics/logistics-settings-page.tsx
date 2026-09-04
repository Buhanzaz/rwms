import { useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Add01Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { toast } from "sonner"

import { OperationsListGrid } from "@/components/operations-list-grid"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
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
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { DriverEditorDialog } from "@/features/settings/logistics/driver-editor-dialog"
import { ShipmentTaskSettingsCard } from "@/features/settings/logistics/shipment-task-settings-card"
import { CredentialPasswordDialog } from "@/features/settings/task-board/settings-editor-dialogs"
import { SettingsDeleteDialog } from "@/features/settings/task-board/settings-delete-dialog"
import {
  taskBoardSettingsClient,
  taskBoardSettingsKeys,
} from "@/features/settings/task-board/api/task-board-settings-api"
import type {
  DriverQueueRequest,
  ParticipationPolicy,
  QueueBindingRequest,
  WorkerClassDto,
  WorkerDto,
  WorkerRequest,
  WorkQueueDto,
} from "@/features/settings/task-board/model/task-board-settings"
import {
  credentialStatusLabels,
  participationPolicyLabels,
} from "@/features/settings/task-board/model/task-board-settings"
import { taskBoardSettingsErrorMessage } from "@/features/settings/task-board/task-board-settings-errors"
import { workerCredentialToggleAction } from "@/features/settings/task-board/worker-credential-action"
import { useAuth } from "@/features/auth/use-auth"
import { useWarehouse } from "@/hooks/use-warehouse"

type DriverQueueEditorProps = {
  section: LogisticsSettingsSection
  queue: WorkQueueDto
  primaryClass: WorkerClassDto
  classes: WorkerClassDto[]
  workers: WorkerDto[]
  pending: boolean
  onCreateDriver: () => void
  onEditDriver: (worker: WorkerDto) => void
  onSave: (request: DriverQueueRequest) => Promise<void>
}

export type LogisticsSettingsSection = "all" | "drivers" | "classes"

type DriverQueueSetupProps = {
  classes: WorkerClassDto[]
  pending: boolean
  onConnect: (primaryClassId: string) => Promise<void>
}

type DriverConfirmation = {
  kind: "credentials" | "worker"
  worker: WorkerDto
}

type DriverActionCommand = {
  execute: () => Promise<unknown>
  success: string
}

function isQualifiedFor(worker: WorkerDto, workerClassId: string) {
  return worker.qualifications.some(
    (qualification) =>
      qualification.active && qualification.workerClass.id === workerClassId
  )
}

function driverCredentialIssueMessage(worker: WorkerDto) {
  if (worker.credentialStatus !== "ERROR") return null

  return "Доступ в приложение не настроен. Нажмите «Изменить», проверьте логин и укажите пароль ещё раз."
}

function DriverQueueSetup({
  classes,
  pending,
  onConnect,
}: DriverQueueSetupProps) {
  const [selectedClassId, setSelectedClassId] = useState(
    classes.length === 1 ? classes[0].id : ""
  )

  return (
    <Card size="sm" className="bg-muted/65">
      <CardHeader>
        <CardTitle>Подключить водителей</CardTitle>
        <CardDescription>
          Объект получит рабочую очередь водителей на основе единого системного
          определения. Пользователи и классы не копируются.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <Field>
          <FieldLabel htmlFor="logistics-primary-class">
            Класс водителей
          </FieldLabel>
          <Select value={selectedClassId} onValueChange={setSelectedClassId}>
            <SelectTrigger id="logistics-primary-class" className="w-full">
              <SelectValue placeholder="Выберите существующий класс" />
            </SelectTrigger>
            <SelectContent>
              <SelectGroup>
                {classes.map((workerClass) => (
                  <SelectItem key={workerClass.id} value={workerClass.id}>
                    {workerClass.name}
                  </SelectItem>
                ))}
              </SelectGroup>
            </SelectContent>
          </Select>
          <FieldDescription>
            Показаны только классы, уже устойчиво связанные с логистической
            очередью. По названию класс не определяется.
          </FieldDescription>
        </Field>
      </CardContent>
      <CardFooter className="justify-end">
        <Button
          type="button"
          disabled={!selectedClassId || pending}
          onClick={() => void onConnect(selectedClassId)}
        >
          {pending ? "Подключаем…" : "Подключить очередь"}
        </Button>
      </CardFooter>
    </Card>
  )
}

function canonicalBindings(queue: WorkQueueDto): QueueBindingRequest[] {
  return [...queue.bindings]
    .sort((left, right) => {
      const leftPrimary = left.primary || left.participationPolicy === "PRIMARY"
      const rightPrimary =
        right.primary || right.participationPolicy === "PRIMARY"
      if (leftPrimary !== rightPrimary) return leftPrimary ? -1 : 1
      return left.order - right.order
    })
    .map((binding, order) => ({
      workerClassId: binding.workerClass.id,
      order,
      stopTaskOnTake: binding.stopTaskOnTake,
      participationPolicy:
        order === 0 ? "PRIMARY" : binding.participationPolicy,
      notifyOnPrimaryTake: order === 0 ? false : binding.notifyOnPrimaryTake,
    }))
}

function DriverQueueEditor({
  section,
  queue,
  primaryClass,
  classes,
  workers,
  pending,
  onCreateDriver,
  onEditDriver,
  onSave,
}: DriverQueueEditorProps) {
  const [bindings, setBindings] = useState<QueueBindingRequest[]>(() =>
    canonicalBindings(queue)
  )
  const [selectedClassId, setSelectedClassId] = useState("")
  const driverWorkers = workers.filter((worker) =>
    isQualifiedFor(worker, primaryClass.id)
  )
  const attachedClassIds = new Set(
    bindings.map((binding) => binding.workerClassId)
  )
  const availableClasses = classes.filter(
    (workerClass) =>
      workerClass.id !== primaryClass.id &&
      workerClass.active &&
      !attachedClassIds.has(workerClass.id)
  )
  const secondaryBindings = bindings.slice(1)

  function attachClass() {
    if (!selectedClassId || attachedClassIds.has(selectedClassId)) return
    setBindings((current) => [
      ...current,
      {
        workerClassId: selectedClassId,
        order: current.length,
        stopTaskOnTake: false,
        participationPolicy: "OPTIONAL",
        notifyOnPrimaryTake: false,
      },
    ])
    setSelectedClassId("")
  }

  function updateSecondary(
    workerClassId: string,
    update: Partial<QueueBindingRequest>
  ) {
    setBindings((current) =>
      current.map((binding) =>
        binding.workerClassId === workerClassId
          ? { ...binding, ...update }
          : binding
      )
    )
  }

  function detachClass(workerClassId: string) {
    setBindings((current) =>
      current
        .filter((binding) => binding.workerClassId !== workerClassId)
        .map((binding, order) => ({ ...binding, order }))
    )
  }

  return (
    <div className="flex flex-col gap-4">
      {section !== "classes" ? (
        <section
          aria-label="Водители"
          className="flex min-h-0 flex-1 flex-col gap-3"
        >
          <div
            className={
              section === "drivers"
                ? "absolute top-0 right-0 z-10 flex h-9 items-center"
                : "flex justify-end"
            }
          >
            <Button type="button" size="sm" onClick={onCreateDriver}>
              <HugeiconsIcon
                icon={Add01Icon}
                data-icon="inline-start"
                aria-hidden="true"
              />
              Создать водителя
            </Button>
          </div>
          {driverWorkers.length > 0 ? (
            <OperationsListGrid
              className="min-h-0 flex-1 overflow-auto bg-muted/70"
              items={driverWorkers}
              columns={[
                {
                  id: "name",
                  label: "Водитель",
                  getSortValue: (item) => item.displayName,
                  render: (item) => item.displayName,
                },
                {
                  id: "classes",
                  label: "Классы",
                  getSortValue: (item) => item.qualifications.length,
                  render: (item) =>
                    item.qualifications
                      .filter((qualification) => qualification.active)
                      .map((qualification) => qualification.workerClass.name)
                      .join(", ") || "—",
                },
                {
                  id: "login",
                  label: "Логин",
                  getSortValue: (item) => item.appLogin,
                  render: (item) => item.appLogin ?? "—",
                },
                {
                  id: "status",
                  label: "Статус",
                  getSortValue: (item) => (item.active ? 1 : 0),
                  render: (item) => (
                    <Badge variant={item.active ? "secondary" : "outline"}>
                      {item.active ? "Активен" : "Отключён"}
                    </Badge>
                  ),
                },
                {
                  id: "credentials",
                  label: "Учётные данные",
                  getSortValue: (item) =>
                    credentialStatusLabels[item.credentialStatus],
                  render: (item) => (
                    <div className="flex flex-col gap-1">
                      <Badge
                        variant={
                          item.credentialStatus === "ERROR"
                            ? "destructive"
                            : "secondary"
                        }
                      >
                        {credentialStatusLabels[item.credentialStatus]}
                      </Badge>
                      {driverCredentialIssueMessage(item) ? (
                        <span className="text-xs text-destructive">
                          {driverCredentialIssueMessage(item)}
                        </span>
                      ) : null}
                    </div>
                  ),
                },
                {
                  id: "actions",
                  label: "Действия",
                  getSortValue: () => null,
                  render: (item) => (
                    <Button
                      type="button"
                      size="sm"
                      variant="outline"
                      onClick={() => onEditDriver(item)}
                    >
                      Изменить
                    </Button>
                  ),
                },
              ]}
            />
          ) : (
            <div className="flex min-h-36 items-center justify-center rounded-lg border bg-muted/55 px-4 text-center text-sm text-muted-foreground">
              Водители пока не добавлены.
            </div>
          )}
        </section>
      ) : null}

      {section !== "drivers" ? (
        <Card size="sm">
          <CardHeader>
            <CardTitle>Дополнительные классы</CardTitle>
            <CardDescription>
              Прикрепляется существующий класс рабочих. Отдельное задание или
              копия класса не создаются.
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <FieldGroup>
              <Field>
                <FieldLabel htmlFor="logistics-additional-class">
                  Класс рабочих
                </FieldLabel>
                <div className="flex flex-col gap-2 sm:flex-row">
                  <Select
                    value={selectedClassId}
                    onValueChange={setSelectedClassId}
                  >
                    <SelectTrigger
                      id="logistics-additional-class"
                      className="w-full"
                    >
                      <SelectValue placeholder="Выберите существующий класс" />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectGroup>
                        {availableClasses.map((workerClass) => (
                          <SelectItem
                            key={workerClass.id}
                            value={workerClass.id}
                          >
                            {workerClass.name}
                          </SelectItem>
                        ))}
                      </SelectGroup>
                    </SelectContent>
                  </Select>
                  <Button
                    type="button"
                    variant="outline"
                    disabled={!selectedClassId}
                    onClick={attachClass}
                  >
                    Прикрепить дополнительный класс
                  </Button>
                </div>
                {availableClasses.length === 0 ? (
                  <FieldDescription>
                    Все доступные классы уже прикреплены.
                  </FieldDescription>
                ) : null}
              </Field>
            </FieldGroup>

            {secondaryBindings.length > 0 ? (
              <FieldGroup>
                {secondaryBindings.map((binding) => {
                  const workerClass = classes.find(
                    (item) => item.id === binding.workerClassId
                  )
                  if (!workerClass) return null

                  return (
                    <Field
                      key={binding.workerClassId}
                      className="rounded-lg border p-3"
                    >
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <span className="font-medium">{workerClass.name}</span>
                        <Button
                          type="button"
                          size="sm"
                          variant="ghost"
                          onClick={() => detachClass(workerClass.id)}
                        >
                          Открепить
                        </Button>
                      </div>
                      <FieldGroup>
                        <Field>
                          <FieldLabel
                            htmlFor={`logistics-policy-${workerClass.id}`}
                          >
                            Участие
                          </FieldLabel>
                          <Select
                            value={binding.participationPolicy}
                            onValueChange={(value) =>
                              updateSecondary(workerClass.id, {
                                participationPolicy:
                                  value as ParticipationPolicy,
                              })
                            }
                          >
                            <SelectTrigger
                              id={`logistics-policy-${workerClass.id}`}
                              className="w-full"
                            >
                              <SelectValue />
                            </SelectTrigger>
                            <SelectContent>
                              <SelectGroup>
                                {(["OPTIONAL", "REQUIRED"] as const).map(
                                  (policy) => (
                                    <SelectItem key={policy} value={policy}>
                                      {participationPolicyLabels[policy]}
                                    </SelectItem>
                                  )
                                )}
                              </SelectGroup>
                            </SelectContent>
                          </Select>
                        </Field>
                        <Field orientation="horizontal">
                          <Checkbox
                            id={`logistics-notify-${workerClass.id}`}
                            checked={binding.notifyOnPrimaryTake}
                            onCheckedChange={(value) =>
                              updateSecondary(workerClass.id, {
                                notifyOnPrimaryTake: value === true,
                              })
                            }
                          />
                          <FieldLabel
                            htmlFor={`logistics-notify-${workerClass.id}`}
                          >
                            Отправлять задание прикреплённому классу после
                            принятия задания водителем
                          </FieldLabel>
                        </Field>
                      </FieldGroup>
                    </Field>
                  )
                })}
              </FieldGroup>
            ) : (
              <p className="text-sm text-muted-foreground">
                Дополнительные классы не прикреплены.
              </p>
            )}
          </CardContent>
          <CardFooter className="justify-end">
            <Button
              type="button"
              disabled={pending}
              onClick={() =>
                void onSave({
                  expectedVersion: queue.version,
                  active: queue.active,
                  hidden: queue.hidden,
                  collapsed: queue.collapsed,
                  holdingPeriodMinutes: queue.holdingPeriodMinutes,
                  notificationThreshold: queue.notificationThreshold,
                  notifyWhenThresholdReached: queue.notifyWhenThresholdReached,
                  resultPhotoMinCount: queue.resultPhotoMinCount,
                  bindings: bindings.map((binding, order) => ({
                    ...binding,
                    order,
                    participationPolicy:
                      order === 0 ? "PRIMARY" : binding.participationPolicy,
                    notifyOnPrimaryTake:
                      order === 0 ? false : binding.notifyOnPrimaryTake,
                  })),
                })
              }
            >
              {pending ? "Сохраняем…" : "Сохранить настройки логистики"}
            </Button>
          </CardFooter>
        </Card>
      ) : null}
    </div>
  )
}

export function LogisticsSettingsPage({
  section = "all",
}: {
  section?: LogisticsSettingsSection
} = {}) {
  const queryClient = useQueryClient()
  const { accessToken, currentUser } = useAuth()
  const { selectedWarehouse } = useWarehouse()
  const [driverEditor, setDriverEditor] = useState<WorkerDto | "new" | null>(
    null
  )
  const [passwordDriver, setPasswordDriver] = useState<WorkerDto | null>(null)
  const [driverConfirmation, setDriverConfirmation] =
    useState<DriverConfirmation | null>(null)
  const warehouseId = selectedWarehouse?.id ?? ""
  const explicitAccess = currentUser?.warehouseAccesses.find(
    (access) => access.warehouseId === warehouseId
  )
  const canManage = Boolean(
    currentUser?.warehouseAccessAll || explicitAccess?.level === "MANAGE"
  )
  const enabled = Boolean(accessToken && warehouseId && canManage)

  const queuesQuery = useQuery({
    queryKey: taskBoardSettingsKeys.queues(warehouseId),
    queryFn: () =>
      taskBoardSettingsClient.listQueues(accessToken!, warehouseId),
    enabled,
  })
  const classesQuery = useQuery({
    queryKey: taskBoardSettingsKeys.classes,
    queryFn: () => taskBoardSettingsClient.listClasses(accessToken!),
    enabled,
  })
  const workersQuery = useQuery({
    queryKey: taskBoardSettingsKeys.workers(warehouseId),
    queryFn: () =>
      taskBoardSettingsClient.listWorkers(accessToken!, warehouseId),
    enabled,
  })
  const mutation = useMutation({
    mutationFn: (request: DriverQueueRequest) => {
      if (!accessToken) {
        throw new Error("Логистическая очередь недоступна.")
      }
      return taskBoardSettingsClient.updateDriverQueue(
        accessToken,
        warehouseId,
        request
      )
    },
    onSuccess: async () => {
      toast.success("Настройки логистики сохранены.")
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: taskBoardSettingsKeys.queues(warehouseId),
        }),
        queryClient.invalidateQueries({
          queryKey: taskBoardSettingsKeys.workers(warehouseId),
        }),
      ])
    },
    onError: (error) => toast.error(taskBoardSettingsErrorMessage(error)),
  })
  const createDriverMutation = useMutation({
    mutationFn: (request: WorkerRequest) => {
      if (!accessToken) {
        throw new Error("Не получен токен доступа к настройкам логистики.")
      }
      return taskBoardSettingsClient.createWorker(
        accessToken,
        warehouseId,
        request
      )
    },
    onSuccess: async (worker) => {
      await queryClient.invalidateQueries({
        queryKey: taskBoardSettingsKeys.workers(warehouseId),
      })
      const credentialIssue = driverCredentialIssueMessage(worker)
      if (credentialIssue) {
        setDriverEditor(worker)
        toast.error("Водитель создан, но доступ в приложение не настроен.")
        return
      }
      setDriverEditor(null)
      toast.success("Водитель создан.")
    },
    onError: (error) => toast.error(taskBoardSettingsErrorMessage(error)),
  })
  const updateDriverMutation = useMutation({
    mutationFn: ({
      worker,
      request,
    }: {
      worker: WorkerDto
      request: WorkerRequest
    }) => {
      if (!accessToken) {
        throw new Error("Не получен токен доступа к настройкам логистики.")
      }
      return taskBoardSettingsClient.updateWorker(
        accessToken,
        warehouseId,
        worker.id,
        request
      )
    },
    onSuccess: async (worker) => {
      await queryClient.invalidateQueries({
        queryKey: taskBoardSettingsKeys.workers(warehouseId),
      })
      const credentialIssue = driverCredentialIssueMessage(worker)
      if (credentialIssue) {
        setDriverEditor(worker)
        toast.error(
          "Профиль водителя сохранён, но доступ в приложение не настроен."
        )
        return
      }
      setDriverEditor(null)
      toast.success("Данные водителя сохранены.")
    },
    onError: (error) => toast.error(taskBoardSettingsErrorMessage(error)),
  })
  const driverActionMutation = useMutation({
    mutationFn: (command: DriverActionCommand) => command.execute(),
    onSuccess: async (_result, command) => {
      setDriverEditor(null)
      setPasswordDriver(null)
      setDriverConfirmation(null)
      toast.success(command.success)
      await queryClient.invalidateQueries({
        queryKey: taskBoardSettingsKeys.workers(warehouseId),
      })
    },
    onError: (error) => toast.error(taskBoardSettingsErrorMessage(error)),
  })

  if (!selectedWarehouse) {
    return (
      <Alert>
        <AlertTitle>Склад не выбран</AlertTitle>
        <AlertDescription>
          Выберите склад, чтобы настроить его логистическую очередь.
        </AlertDescription>
      </Alert>
    )
  }
  if (!canManage) {
    return (
      <Alert>
        <AlertTitle>Недостаточно прав</AlertTitle>
        <AlertDescription>
          Для настройки логистики нужен уровень MANAGE выбранного склада.
        </AlertDescription>
      </Alert>
    )
  }

  const independentSettings =
    section === "all" ? (
      <>
        <ShipmentTaskSettingsCard
          accessToken={accessToken!}
          warehouseId={warehouseId}
          warehouseName={selectedWarehouse.name}
        />
      </>
    ) : null

  const queryError =
    queuesQuery.error ?? classesQuery.error ?? workersQuery.error
  if (queryError) {
    return (
      <div className="flex flex-col gap-4">
        <Alert variant="destructive">
          <AlertTitle>Не удалось загрузить настройки логистики</AlertTitle>
          <AlertDescription>
            {taskBoardSettingsErrorMessage(queryError)}
          </AlertDescription>
        </Alert>
        {independentSettings}
      </div>
    )
  }
  if (
    queuesQuery.isLoading ||
    classesQuery.isLoading ||
    workersQuery.isLoading
  ) {
    return (
      <div className="flex flex-col gap-4">
        <p className="text-sm text-muted-foreground">
          Загрузка настроек логистики…
        </p>
        {independentSettings}
      </div>
    )
  }

  const logisticsQueues = (queuesQuery.data ?? []).filter(
    (queue) => queue.purpose === "LOGISTICS_DRIVER"
  )
  if (logisticsQueues.length > 1) {
    return (
      <div className="flex flex-col gap-4">
        <Alert variant="destructive">
          <AlertTitle>Неверная конфигурация очереди водителей</AlertTitle>
          <AlertDescription>
            К складу подключено несколько очередей водителей. Должна остаться
            ровно одна.
          </AlertDescription>
        </Alert>
        {independentSettings}
      </div>
    )
  }

  if (logisticsQueues.length === 0) {
    const logisticsPrimaryClasses = (classesQuery.data ?? []).filter(
      (workerClass) => workerClass.active && workerClass.logisticsPrimary
    )
    return (
      <div className="flex flex-col gap-4">
        {logisticsPrimaryClasses.length > 0 ? (
          <DriverQueueSetup
            classes={logisticsPrimaryClasses}
            pending={mutation.isPending}
            onConnect={async (primaryClassId) => {
              await mutation.mutateAsync({
                expectedVersion: 0,
                active: true,
                hidden: false,
                collapsed: false,
                holdingPeriodMinutes: null,
                notificationThreshold: null,
                notifyWhenThresholdReached: false,
                resultPhotoMinCount: 1,
                bindings: [
                  {
                    workerClassId: primaryClassId,
                    order: 0,
                    stopTaskOnTake: false,
                    participationPolicy: "PRIMARY",
                    notifyOnPrimaryTake: false,
                  },
                ],
              })
            }}
          />
        ) : (
          <Alert variant="destructive">
            <AlertTitle>Не найден класс водителей</AlertTitle>
            <AlertDescription>
              В системе нет активного класса, устойчиво связанного с
              логистической очередью. Скрытый поиск по названию не выполняется.
            </AlertDescription>
          </Alert>
        )}
        {independentSettings}
      </div>
    )
  }

  const queue = logisticsQueues[0]
  const primaryBindings = queue.bindings.filter(
    (binding) => binding.primary || binding.participationPolicy === "PRIMARY"
  )
  if (primaryBindings.length !== 1) {
    return (
      <div className="flex flex-col gap-4">
        <Alert variant="destructive">
          <AlertTitle>Не определён класс водителей</AlertTitle>
          <AlertDescription>
            У логистической очереди должна быть ровно одна связь с классом
            водителей.
          </AlertDescription>
        </Alert>
        {independentSettings}
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-4">
      <DriverQueueEditor
        key={`${queue.id}:${queue.version}`}
        section={section}
        queue={queue}
        primaryClass={primaryBindings[0].workerClass}
        classes={classesQuery.data ?? []}
        workers={workersQuery.data ?? []}
        pending={mutation.isPending}
        onCreateDriver={() => {
          createDriverMutation.reset()
          updateDriverMutation.reset()
          driverActionMutation.reset()
          setDriverEditor("new")
        }}
        onEditDriver={(worker) => {
          createDriverMutation.reset()
          updateDriverMutation.reset()
          driverActionMutation.reset()
          setDriverEditor(worker)
        }}
        onSave={async (request) => {
          await mutation.mutateAsync(request)
        }}
      />
      {driverEditor ? (
        <DriverEditorDialog
          key={
            driverEditor === "new"
              ? "new"
              : `${driverEditor.id}:${driverEditor.version}`
          }
          worker={driverEditor === "new" ? null : driverEditor}
          primaryClassId={primaryBindings[0].workerClass.id}
          pending={
            driverEditor === "new"
              ? createDriverMutation.isPending
              : updateDriverMutation.isPending
          }
          error={
            driverEditor === "new"
              ? createDriverMutation.error
                ? taskBoardSettingsErrorMessage(createDriverMutation.error)
                : null
              : updateDriverMutation.error
                ? taskBoardSettingsErrorMessage(updateDriverMutation.error)
                : driverActionMutation.error
                  ? taskBoardSettingsErrorMessage(driverActionMutation.error)
                  : null
          }
          onClose={() => {
            createDriverMutation.reset()
            updateDriverMutation.reset()
            setDriverEditor(null)
          }}
          onResetPassword={
            driverEditor === "new"
              ? undefined
              : () => {
                  setDriverEditor(null)
                  driverActionMutation.reset()
                  setPasswordDriver(driverEditor)
                }
          }
          onDisableCredentials={
            driverEditor === "new" ||
            workerCredentialToggleAction(driverEditor.credentialStatus) !==
              "DISABLE"
              ? undefined
              : () => {
                  setDriverEditor(null)
                  driverActionMutation.reset()
                  setDriverConfirmation({
                    kind: "credentials",
                    worker: driverEditor,
                  })
                }
          }
          onEnableCredentials={
            driverEditor === "new" ||
            workerCredentialToggleAction(driverEditor.credentialStatus) !==
              "ENABLE"
              ? undefined
              : () =>
                  driverActionMutation.mutate({
                    execute: () =>
                      taskBoardSettingsClient.enableWorkerCredentials(
                        accessToken!,
                        warehouseId,
                        driverEditor.id,
                        driverEditor.version
                      ),
                    success: "Вход водителя включён.",
                  })
          }
          onDelete={
            driverEditor === "new"
              ? undefined
              : () => {
                  setDriverEditor(null)
                  driverActionMutation.reset()
                  setDriverConfirmation({
                    kind: "worker",
                    worker: driverEditor,
                  })
                }
          }
          onSave={async (request) => {
            if (driverEditor === "new") {
              await createDriverMutation.mutateAsync(request)
              return
            }
            await updateDriverMutation.mutateAsync({
              worker: driverEditor,
              request,
            })
          }}
        />
      ) : null}
      {passwordDriver ? (
        <CredentialPasswordDialog
          key={passwordDriver.id}
          worker={passwordDriver}
          pending={driverActionMutation.isPending}
          error={
            driverActionMutation.error
              ? taskBoardSettingsErrorMessage(driverActionMutation.error)
              : null
          }
          onClose={() => {
            driverActionMutation.reset()
            setPasswordDriver(null)
          }}
          onSave={async (password) => {
            await driverActionMutation.mutateAsync({
              execute: () =>
                taskBoardSettingsClient.resetWorkerCredentials(
                  accessToken!,
                  warehouseId,
                  passwordDriver.id,
                  passwordDriver.version,
                  password
                ),
              success: "Пароль водителя изменён.",
            })
          }}
        />
      ) : null}
      {driverConfirmation ? (
        <SettingsDeleteDialog
          title={
            driverConfirmation.kind === "credentials"
              ? "Отключить вход водителя?"
              : "Удалить водителя?"
          }
          description={
            driverConfirmation.kind === "credentials"
              ? "Водитель больше не сможет входить в приложение. Профиль и история сохранятся."
              : "Если водитель уже используется, сервис отклонит удаление и предложит деактивацию."
          }
          confirmLabel={
            driverConfirmation.kind === "credentials" ? "Отключить" : "Удалить"
          }
          pending={driverActionMutation.isPending}
          error={
            driverActionMutation.error
              ? taskBoardSettingsErrorMessage(driverActionMutation.error)
              : null
          }
          onClose={() => {
            driverActionMutation.reset()
            setDriverConfirmation(null)
          }}
          onConfirm={async () => {
            const { kind, worker } = driverConfirmation
            await driverActionMutation.mutateAsync({
              execute: () =>
                kind === "credentials"
                  ? taskBoardSettingsClient.disableWorkerCredentials(
                      accessToken!,
                      warehouseId,
                      worker.id,
                      worker.version
                    )
                  : taskBoardSettingsClient.deleteWorker(
                      accessToken!,
                      warehouseId,
                      worker.id,
                      worker.version
                    ),
              success:
                kind === "credentials"
                  ? "Вход водителя отключён."
                  : "Водитель удалён.",
            })
          }}
        />
      ) : null}
      {independentSettings}
    </div>
  )
}
