import { useRef, useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { HugeiconsIcon } from "@hugeicons/react"
import {
  Queue01Icon,
  Task01Icon,
  UserGroupIcon,
  UserIcon,
} from "@hugeicons/core-free-icons"
import { toast } from "sonner"

import { OperationsListGrid } from "@/components/operations-list-grid"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import { isGlobalAdministrator } from "@/features/auth/auth-model"
import { useAuth } from "@/features/auth/use-auth"
import {
  taskBoardSettingsClient,
  taskBoardSettingsKeys,
} from "@/features/settings/task-board/api/task-board-settings-api"
import type {
  QueueDefinitionDto,
  QueueDefinitionRequest,
  WorkerClassDto,
  WorkerClassRequest,
  WorkerDto,
  WorkerGroupDto,
  WorkerGroupRequest,
  WorkerRequest,
} from "@/features/settings/task-board/model/task-board-settings"
import {
  credentialStatusLabels,
  operationalAvailabilityLabels,
  queueTypeLabels,
} from "@/features/settings/task-board/model/task-board-settings"
import {
  ClassEditorDialog,
  CredentialPasswordDialog,
  GroupAvailabilityDialog,
  GroupEditorDialog,
  QueueDefinitionEditorDialog,
  WorkerEditorDialog,
} from "@/features/settings/task-board/settings-editor-dialogs"
import { SettingsDeleteDialog } from "@/features/settings/task-board/settings-delete-dialog"
import {
  isTaskBoardSettingsConflict as isConflict,
  taskBoardSettingsErrorMessage as message,
} from "@/features/settings/task-board/task-board-settings-errors"
import { workerCredentialToggleAction } from "@/features/settings/task-board/worker-credential-action"
import { useWarehouse } from "@/hooks/use-warehouse"

type DeleteTarget =
  | { kind: "queue-definition"; item: QueueDefinitionDto }
  | { kind: "class"; item: WorkerClassDto }
  | { kind: "group"; item: WorkerGroupDto }
  | { kind: "worker"; item: WorkerDto }
  | { kind: "credentials"; item: WorkerDto }

type MutationCommand = {
  execute: () => Promise<unknown>
  success: string
  closeOnConflict: boolean
}

function StatusBadge({ active }: { active: boolean }) {
  return (
    <Badge variant={active ? "secondary" : "outline"}>
      {active ? "Активно" : "Отключено"}
    </Badge>
  )
}

function QueueBindings({ queue }: { queue: QueueDefinitionDto }) {
  const bindings = [...queue.bindings].sort(
    (left, right) => left.order - right.order
  )

  if (bindings.length === 0) return "—"

  return (
    <div className="flex flex-wrap gap-x-2 gap-y-1">
      {bindings.map((binding) => (
        <span key={binding.id}>{binding.workerClass.name}</span>
      ))}
    </div>
  )
}

const taskBoardSettingsSections = [
  { value: "queue-definitions", label: "Доски задач", icon: Queue01Icon },
  { value: "classes", label: "Классы", icon: Task01Icon },
  { value: "groups", label: "Бригады", icon: UserGroupIcon },
  { value: "workers", label: "Рабочие", icon: UserIcon },
] as const

export type TaskBoardSettingsSection =
  (typeof taskBoardSettingsSections)[number]["value"]

export function TaskBoardSettingsPage({
  section,
}: {
  section?: TaskBoardSettingsSection
} = {}) {
  const queryClient = useQueryClient()
  const { accessToken, currentUser } = useAuth()
  const { selectedWarehouse } = useWarehouse()
  const warehouseId = selectedWarehouse?.id ?? ""
  const [queueDefinitionEditor, setQueueDefinitionEditor] = useState<
    QueueDefinitionDto | "new" | null
  >(null)
  const [classEditor, setClassEditor] = useState<WorkerClassDto | "new" | null>(
    null
  )
  const [workerEditor, setWorkerEditor] = useState<WorkerDto | "new" | null>(
    null
  )
  const [groupEditor, setGroupEditor] = useState<WorkerGroupDto | "new" | null>(
    null
  )
  const [passwordWorker, setPasswordWorker] = useState<WorkerDto | null>(null)
  const [availabilityGroup, setAvailabilityGroup] =
    useState<WorkerGroupDto | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<DeleteTarget | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [activeSection, setActiveSection] =
    useState<TaskBoardSettingsSection>("queue-definitions")
  const visibleSection = section ?? activeSection
  const mutationInFlight = useRef(false)

  const explicitAccess = currentUser?.warehouseAccesses.find(
    (access) => access.warehouseId === warehouseId
  )
  const canManage = Boolean(
    currentUser?.warehouseAccessAll || explicitAccess?.level === "MANAGE"
  )
  const canManageGlobal = Boolean(
    currentUser && isGlobalAdministrator(currentUser.globalRole)
  )
  const canViewGlobalCatalog = canManage || canManageGlobal

  const queueDefinitionsQuery = useQuery({
    queryKey: taskBoardSettingsKeys.queueDefinitions,
    queryFn: () => taskBoardSettingsClient.listQueueDefinitions(accessToken!),
    enabled: Boolean(accessToken && canViewGlobalCatalog),
  })
  const classesQuery = useQuery({
    queryKey: taskBoardSettingsKeys.classes,
    queryFn: () => taskBoardSettingsClient.listClasses(accessToken!),
    enabled: Boolean(accessToken && canViewGlobalCatalog),
  })
  const workersQuery = useQuery({
    queryKey: taskBoardSettingsKeys.workers(warehouseId),
    queryFn: () =>
      taskBoardSettingsClient.listWorkers(accessToken!, warehouseId),
    enabled: Boolean(accessToken && warehouseId && canManage),
  })
  const groupsQuery = useQuery({
    queryKey: taskBoardSettingsKeys.groups(warehouseId),
    queryFn: () =>
      taskBoardSettingsClient.listGroups(accessToken!, warehouseId),
    enabled: Boolean(accessToken && warehouseId && canManage),
  })

  function closeEditors() {
    setQueueDefinitionEditor(null)
    setClassEditor(null)
    setWorkerEditor(null)
    setGroupEditor(null)
    setPasswordWorker(null)
    setAvailabilityGroup(null)
    setDeleteTarget(null)
    setActionError(null)
  }

  async function invalidateSettings() {
    await Promise.all([
      queryClient.invalidateQueries({
        queryKey: taskBoardSettingsKeys.queueDefinitions,
      }),
      queryClient.invalidateQueries({
        queryKey: taskBoardSettingsKeys.classes,
      }),
      queryClient.invalidateQueries({
        queryKey: taskBoardSettingsKeys.workers(warehouseId),
      }),
      queryClient.invalidateQueries({
        queryKey: taskBoardSettingsKeys.groups(warehouseId),
      }),
    ])
  }

  const mutation = useMutation({
    mutationFn: (command: MutationCommand) => command.execute(),
    onSuccess: async (_result, command) => {
      closeEditors()
      toast.success(command.success)
      await invalidateSettings()
    },
    onError: async (error, command) => {
      const staleSelection = command.closeOnConflict && isConflict(error)
      const text = message(error, staleSelection)

      if (staleSelection) {
        closeEditors()
      } else {
        setActionError(text)
      }

      toast.error(text)
      if (staleSelection) {
        await invalidateSettings()
      }
    },
  })
  async function run(
    execute: () => Promise<unknown>,
    success: string,
    closeOnConflict = true
  ) {
    if (mutationInFlight.current) return
    mutationInFlight.current = true
    setActionError(null)
    try {
      await mutation.mutateAsync({ execute, success, closeOnConflict })
    } catch {
      // onError owns the visible ProblemDetail/409 presentation and refetch.
    } finally {
      mutationInFlight.current = false
    }
  }

  const requiresWarehouse =
    visibleSection === "groups" || visibleSection === "workers"
  if (requiresWarehouse && !selectedWarehouse) {
    return (
      <Card size="sm">
        <CardContent className="text-sm text-muted-foreground">
          Выберите склад.
        </CardContent>
      </Card>
    )
  }
  if (requiresWarehouse && !canManage) {
    return (
      <Card size="sm">
        <CardContent className="text-sm text-muted-foreground">
          Для настройки доски нужен уровень MANAGE выбранного склада.
        </CardContent>
      </Card>
    )
  }
  if (!canViewGlobalCatalog) {
    return (
      <Card size="sm">
        <CardContent className="text-sm text-muted-foreground">
          Для просмотра каталога очередей нужен уровень MANAGE выбранного
          склада.
        </CardContent>
      </Card>
    )
  }

  const queryError =
    queueDefinitionsQuery.error ??
    classesQuery.error ??
    workersQuery.error ??
    groupsQuery.error
  if (queryError) {
    return (
      <Card size="sm">
        <CardContent className="flex flex-col gap-3 text-sm text-destructive">
          <p role="alert">{message(queryError)}</p>
          <Button variant="outline" onClick={() => void invalidateSettings()}>
            Повторить
          </Button>
        </CardContent>
      </Card>
    )
  }
  if (
    queueDefinitionsQuery.isLoading ||
    classesQuery.isLoading ||
    workersQuery.isLoading ||
    groupsQuery.isLoading
  ) {
    return (
      <p className="text-sm text-muted-foreground">Загрузка настроек доски…</p>
    )
  }

  const allQueueDefinitions = queueDefinitionsQuery.data ?? []
  const allClasses = classesQuery.data ?? []
  const allWorkers = workersQuery.data ?? []
  const allGroups = groupsQuery.data ?? []
  const queueDefinitions = allQueueDefinitions
    // Purpose, rather than the displayed type/name, identifies the common
    // task-board standard. A MOVEMENT queue may still be a normal global
    // board queue; the dedicated driver queue is separated by
    // LOGISTICS_DRIVER purpose.
    .filter((definition) => definition.purpose === "GENERAL")
    .sort(
      (left, right) =>
        left.sortOrder - right.sortOrder || left.name.localeCompare(right.name)
    )
  const classes = allClasses.filter((item) => !item.logisticsPrimary)
  const workers = allWorkers.filter(
    (worker) =>
      !worker.qualifications.some(
        (qualification) =>
          qualification.active && qualification.workerClass.logisticsPrimary
      )
  )
  const groups = allGroups.filter(
    (group) => !group.workerClass.logisticsPrimary
  )

  async function moveQueueDefinition(
    definition: QueueDefinitionDto,
    direction: -1 | 1
  ) {
    if (!accessToken || definition.type === "HOLDING") return
    const regular = queueDefinitions.filter((item) => item.type !== "HOLDING")
    const holding = queueDefinitions.filter((item) => item.type === "HOLDING")
    const index = regular.findIndex((item) => item.id === definition.id)
    const nextIndex = index + direction

    if (index < 0 || nextIndex < 0 || nextIndex >= regular.length) return

    const reordered = [...regular]
    ;[reordered[index], reordered[nextIndex]] = [
      reordered[nextIndex],
      reordered[index],
    ]

    await run(
      () =>
        taskBoardSettingsClient.reorderQueueDefinitions(
          accessToken,
          [...reordered, ...holding].map((item) => ({
            definitionId: item.id,
            expectedVersion: item.version,
          }))
        ),
      "Порядок очередей сохранён."
    )
  }
  async function confirmDelete() {
    if (!deleteTarget || !accessToken) return
    const target = deleteTarget
    switch (target.kind) {
      case "queue-definition":
        return run(
          () =>
            taskBoardSettingsClient.deleteQueueDefinition(
              accessToken,
              target.item.id,
              target.item.version
            ),
          "Общая очередь удалена."
        )
      case "class":
        return run(
          () =>
            taskBoardSettingsClient.deleteClass(
              accessToken,
              target.item.id,
              target.item.version
            ),
          "Класс удалён."
        )
      case "group":
        return run(
          () =>
            taskBoardSettingsClient.deleteGroup(
              accessToken,
              warehouseId,
              target.item.id,
              target.item.version
            ),
          "Бригада удалена."
        )
      case "worker":
        return run(
          () =>
            taskBoardSettingsClient.deleteWorker(
              accessToken,
              warehouseId,
              target.item.id,
              target.item.version
            ),
          "Рабочий удалён."
        )
      case "credentials":
        return run(
          () =>
            taskBoardSettingsClient.disableWorkerCredentials(
              accessToken,
              warehouseId,
              target.item.id,
              target.item.version
            ),
          "Учётные данные отключены."
        )
    }
  }

  async function enableWorkerCredentials(worker: WorkerDto) {
    if (!accessToken) return
    await run(
      () =>
        taskBoardSettingsClient.enableWorkerCredentials(
          accessToken,
          warehouseId,
          worker.id,
          worker.version
        ),
      "Учётные данные включены."
    )
  }

  async function enableGroup(group: WorkerGroupDto) {
    if (!accessToken) return
    await run(
      () =>
        taskBoardSettingsClient.enableGroup(
          accessToken,
          warehouseId,
          group.id,
          group.version,
          null
        ),
      "Бригада снова доступна."
    )
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-4">
      {section === undefined ? (
        <nav aria-label="Разделы настройки доски задач">
          <ToggleGroup
            type="single"
            value={activeSection}
            variant="outline"
            size="lg"
            className="grid w-full grid-cols-2 gap-2 sm:grid-cols-4 lg:grid-cols-4"
            onValueChange={(nextSection) => {
              if (nextSection) {
                setActiveSection(nextSection as TaskBoardSettingsSection)
              }
            }}
          >
            {taskBoardSettingsSections.map((item) => (
              <ToggleGroupItem
                key={item.value}
                value={item.value}
                className="h-9 w-full justify-center"
              >
                <HugeiconsIcon
                  icon={item.icon}
                  data-icon="inline-start"
                  aria-hidden="true"
                />
                <span className="truncate">{item.label}</span>
              </ToggleGroupItem>
            ))}
          </ToggleGroup>
        </nav>
      ) : null}

      {visibleSection === "queue-definitions" ? (
        <section
          aria-label="Доски задач"
          className="flex min-h-0 flex-1 flex-col gap-3"
        >
          {canManageGlobal ? (
            <div className="flex justify-end">
              <Button
                type="button"
                onClick={() => setQueueDefinitionEditor("new")}
              >
                Создать очередь
              </Button>
            </div>
          ) : null}
          <OperationsListGrid
            className="min-h-0 flex-1 overflow-auto"
            items={queueDefinitions}
            columns={[
              {
                id: "order",
                label: "Позиция",
                getSortValue: (item) => item.sortOrder,
                render: (item) => {
                  const regular = queueDefinitions.filter(
                    (definition) => definition.type !== "HOLDING"
                  )
                  const index = regular.findIndex(
                    (definition) => definition.id === item.id
                  )
                  const terminal = item.type === "HOLDING"

                  return (
                    <div className="flex items-center gap-2">
                      <span>{item.sortOrder}</span>
                      {canManageGlobal && !terminal ? (
                        <div className="flex items-center gap-1">
                          <Button
                            type="button"
                            size="sm"
                            variant="outline"
                            disabled={index === 0 || mutation.isPending}
                            aria-label={`Поднять очередь ${item.name}`}
                            onClick={() => void moveQueueDefinition(item, -1)}
                          >
                            Выше
                          </Button>
                          <Button
                            type="button"
                            size="sm"
                            variant="outline"
                            disabled={
                              index === regular.length - 1 || mutation.isPending
                            }
                            aria-label={`Опустить очередь ${item.name}`}
                            onClick={() => void moveQueueDefinition(item, 1)}
                          >
                            Ниже
                          </Button>
                        </div>
                      ) : terminal ? (
                        <span className="text-xs text-muted-foreground">
                          В конце
                        </span>
                      ) : null}
                    </div>
                  )
                },
              },
              {
                id: "name",
                label: "Название",
                getSortValue: (item) => item.name,
                render: (item) => item.name,
              },
              {
                id: "type",
                label: "Тип",
                getSortValue: (item) => queueTypeLabels[item.type],
                render: (item) => queueTypeLabels[item.type],
              },
              {
                id: "classes",
                label: "Классы исполнителей",
                getSortValue: (item) => item.bindings.length,
                render: (item) => <QueueBindings queue={item} />,
              },
              {
                id: "availableTaskLimit",
                label: "План новых складов",
                getSortValue: (item) => item.availableTaskLimit,
                render: (item) => item.availableTaskLimit,
              },
              {
                id: "status",
                label: "Статус",
                getSortValue: (item) => (item.active ? 1 : 0),
                render: (item) => <StatusBadge active={item.active} />,
              },
              {
                id: "actions",
                label: "Действия",
                getSortValue: () => null,
                render: (item) =>
                  canManageGlobal ? (
                    <Button
                      type="button"
                      size="sm"
                      variant="outline"
                      onClick={() => setQueueDefinitionEditor(item)}
                    >
                      Изменить
                    </Button>
                  ) : (
                    "Только просмотр"
                  ),
              },
            ]}
          />
        </section>
      ) : null}

      {visibleSection === "classes" ? (
        <section
          aria-label="Классы"
          className="flex min-h-0 flex-1 flex-col gap-3"
        >
          {canManageGlobal ? (
            <div className="flex justify-end">
              <Button type="button" onClick={() => setClassEditor("new")}>
                Создать класс
              </Button>
            </div>
          ) : null}
          <OperationsListGrid
            className="min-h-0 flex-1 overflow-auto"
            items={classes}
            columns={[
              {
                id: "name",
                label: "Название",
                getSortValue: (item) => item.name,
                render: (item) => item.name,
              },
              {
                id: "order",
                label: "Порядок",
                getSortValue: (item) => item.sortOrder,
                render: (item) => item.sortOrder,
              },
              {
                id: "status",
                label: "Статус",
                getSortValue: (item) => (item.active ? 1 : 0),
                render: (item) => <StatusBadge active={item.active} />,
              },
              {
                id: "actions",
                label: "Действия",
                getSortValue: () => null,
                render: (item) =>
                  canManageGlobal ? (
                    <Button
                      type="button"
                      size="sm"
                      variant="outline"
                      onClick={() => setClassEditor(item)}
                    >
                      Изменить
                    </Button>
                  ) : (
                    "Только просмотр"
                  ),
              },
            ]}
          />
        </section>
      ) : null}

      {visibleSection === "groups" ? (
        <section
          aria-label="Бригады"
          className="flex min-h-0 flex-1 flex-col gap-3"
        >
          <div
            className={
              section === "groups"
                ? "absolute top-0 right-0 z-10 flex h-9 items-center"
                : "flex justify-end"
            }
          >
            <Button type="button" onClick={() => setGroupEditor("new")}>
              Создать бригаду
            </Button>
          </div>
          <OperationsListGrid
            className="min-h-0 flex-1 overflow-auto"
            items={groups}
            columns={[
              {
                id: "name",
                label: "Название",
                getSortValue: (item) => item.name,
                render: (item) => item.name,
              },
              {
                id: "class",
                label: "Класс",
                getSortValue: (item) => item.workerClass.name,
                render: (item) => item.workerClass.name,
              },
              {
                id: "members",
                label: "Участники",
                getSortValue: (item) =>
                  item.members.filter((m) => m.active).length,
                render: (item) =>
                  item.members
                    .filter((m) => m.active)
                    .map((m) => m.workerName)
                    .join(", ") || "—",
              },
              {
                id: "status",
                label: "Статус",
                getSortValue: (item) => (item.active ? 1 : 0),
                render: (item) => <StatusBadge active={item.active} />,
              },
              {
                id: "availability",
                label: "Доступность",
                getSortValue: (item) =>
                  operationalAvailabilityLabels[item.operationalStatus],
                render: (item) => (
                  <div className="flex flex-col gap-1">
                    <Badge
                      variant={
                        item.operationalStatus === "AVAILABLE"
                          ? "secondary"
                          : "outline"
                      }
                    >
                      {operationalAvailabilityLabels[item.operationalStatus]}
                    </Badge>
                    {item.unavailabilityReason ? (
                      <span className="text-xs text-muted-foreground">
                        {item.unavailabilityReason}
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
                    onClick={() => setGroupEditor(item)}
                  >
                    Изменить
                  </Button>
                ),
              },
            ]}
          />
        </section>
      ) : null}

      {visibleSection === "workers" ? (
        <section
          aria-label="Рабочие"
          className="flex min-h-0 flex-1 flex-col gap-3"
        >
          <div
            className={
              section === "workers"
                ? "absolute top-0 right-0 z-10 flex h-9 items-center"
                : "flex justify-end"
            }
          >
            <Button type="button" onClick={() => setWorkerEditor("new")}>
              Создать рабочего
            </Button>
          </div>
          <OperationsListGrid
            className="min-h-0 flex-1 overflow-auto"
            items={workers}
            columns={[
              {
                id: "name",
                label: "Рабочий",
                getSortValue: (item) => item.displayName,
                render: (item) => item.displayName,
              },
              {
                id: "classes",
                label: "Классы",
                getSortValue: (item) => item.qualifications.length,
                render: (item) =>
                  item.qualifications
                    .filter((q) => q.active && !q.workerClass.logisticsPrimary)
                    .map((q) => q.workerClass.name)
                    .join(", ") || "—",
              },
              {
                id: "login",
                label: "Логин",
                getSortValue: (item) => item.appLogin,
                render: (item) => item.appLogin ?? "—",
              },
              {
                id: "current-group",
                label: "Текущая бригада",
                getSortValue: (item) => item.currentGroupName,
                render: (item) => (
                  <div className="flex flex-col gap-1">
                    <Badge
                      variant={item.currentGroupId ? "secondary" : "outline"}
                    >
                      {item.currentGroupName ?? "Не выбрана"}
                    </Badge>
                    {item.operationalAvailability === "DISABLED" ? (
                      <span className="text-xs text-muted-foreground">
                        Бригада недоступна
                      </span>
                    ) : null}
                  </div>
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
                    {item.credentialError ? (
                      <span className="text-xs text-destructive">
                        {item.credentialError}
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
                    onClick={() => setWorkerEditor(item)}
                  >
                    Изменить
                  </Button>
                ),
              },
            ]}
          />
        </section>
      ) : null}

      {queueDefinitionEditor ? (
        <QueueDefinitionEditorDialog
          key={
            queueDefinitionEditor === "new" ? "new" : queueDefinitionEditor.id
          }
          definition={
            queueDefinitionEditor === "new" ? null : queueDefinitionEditor
          }
          classes={classes}
          pending={mutation.isPending}
          error={actionError}
          onClose={closeEditors}
          onDelete={
            queueDefinitionEditor === "new"
              ? undefined
              : () => {
                  setQueueDefinitionEditor(null)
                  setActionError(null)
                  setDeleteTarget({
                    kind: "queue-definition",
                    item: queueDefinitionEditor,
                  })
                }
          }
          onSave={async (request: QueueDefinitionRequest) => {
            if (!accessToken) return
            await run(
              () =>
                queueDefinitionEditor === "new"
                  ? taskBoardSettingsClient.createQueueDefinition(
                      accessToken,
                      request
                    )
                  : taskBoardSettingsClient.updateQueueDefinition(
                      accessToken,
                      queueDefinitionEditor.id,
                      request
                    ),
              "Общая очередь сохранена.",
              queueDefinitionEditor !== "new"
            )
          }}
        />
      ) : null}
      {classEditor ? (
        <ClassEditorDialog
          key={classEditor === "new" ? "new" : classEditor.id}
          item={classEditor === "new" ? null : classEditor}
          pending={mutation.isPending}
          error={actionError}
          onClose={closeEditors}
          onDelete={
            classEditor === "new"
              ? undefined
              : () => {
                  setClassEditor(null)
                  setActionError(null)
                  setDeleteTarget({ kind: "class", item: classEditor })
                }
          }
          onSave={async (request: WorkerClassRequest) => {
            if (!accessToken) return
            await run(
              () =>
                classEditor === "new"
                  ? taskBoardSettingsClient.createClass(accessToken, request)
                  : taskBoardSettingsClient.updateClass(
                      accessToken,
                      classEditor.id,
                      request
                    ),
              "Класс сохранён.",
              classEditor !== "new"
            )
          }}
        />
      ) : null}
      {workerEditor ? (
        <WorkerEditorDialog
          key={workerEditor === "new" ? "new" : workerEditor.id}
          item={workerEditor === "new" ? null : workerEditor}
          classes={classes}
          pending={mutation.isPending}
          error={actionError}
          onClose={closeEditors}
          onResetPassword={
            workerEditor === "new"
              ? undefined
              : () => {
                  setWorkerEditor(null)
                  setActionError(null)
                  setPasswordWorker(workerEditor)
                }
          }
          onDisableCredentials={
            workerEditor === "new" ||
            workerCredentialToggleAction(workerEditor.credentialStatus) !==
              "DISABLE"
              ? undefined
              : () => {
                  setWorkerEditor(null)
                  setActionError(null)
                  setDeleteTarget({ kind: "credentials", item: workerEditor })
                }
          }
          onEnableCredentials={
            workerEditor === "new" ||
            workerCredentialToggleAction(workerEditor.credentialStatus) !==
              "ENABLE"
              ? undefined
              : () => void enableWorkerCredentials(workerEditor)
          }
          onDelete={
            workerEditor === "new"
              ? undefined
              : () => {
                  setWorkerEditor(null)
                  setActionError(null)
                  setDeleteTarget({ kind: "worker", item: workerEditor })
                }
          }
          onSave={async (request: WorkerRequest) => {
            if (!accessToken) return
            await run(
              () =>
                workerEditor === "new"
                  ? taskBoardSettingsClient.createWorker(
                      accessToken,
                      warehouseId,
                      request
                    )
                  : taskBoardSettingsClient.updateWorker(
                      accessToken,
                      warehouseId,
                      workerEditor.id,
                      request
                    ),
              "Рабочий сохранён.",
              workerEditor !== "new"
            )
          }}
        />
      ) : null}
      {groupEditor ? (
        <GroupEditorDialog
          key={groupEditor === "new" ? "new" : groupEditor.id}
          item={groupEditor === "new" ? null : groupEditor}
          classes={classes}
          workers={workers}
          pending={mutation.isPending}
          error={actionError}
          onClose={closeEditors}
          onDisableAvailability={
            groupEditor === "new" ||
            groupEditor.operationalStatus !== "AVAILABLE"
              ? undefined
              : () => {
                  setGroupEditor(null)
                  setActionError(null)
                  setAvailabilityGroup(groupEditor)
                }
          }
          onEnableAvailability={
            groupEditor === "new" ||
            groupEditor.operationalStatus === "AVAILABLE"
              ? undefined
              : () => void enableGroup(groupEditor)
          }
          onDelete={
            groupEditor === "new"
              ? undefined
              : () => {
                  setGroupEditor(null)
                  setActionError(null)
                  setDeleteTarget({ kind: "group", item: groupEditor })
                }
          }
          onSave={async (request: WorkerGroupRequest) => {
            if (!accessToken) return
            await run(
              () =>
                groupEditor === "new"
                  ? taskBoardSettingsClient.createGroup(
                      accessToken,
                      warehouseId,
                      request
                    )
                  : taskBoardSettingsClient.updateGroup(
                      accessToken,
                      warehouseId,
                      groupEditor.id,
                      request
                    ),
              "Бригада сохранена.",
              groupEditor !== "new"
            )
          }}
        />
      ) : null}
      {passwordWorker ? (
        <CredentialPasswordDialog
          key={passwordWorker.id}
          worker={passwordWorker}
          pending={mutation.isPending}
          error={actionError}
          onClose={closeEditors}
          onSave={async (password) => {
            if (!accessToken) return
            await run(
              () =>
                taskBoardSettingsClient.resetWorkerCredentials(
                  accessToken,
                  warehouseId,
                  passwordWorker.id,
                  passwordWorker.version,
                  password
                ),
              "Пароль рабочего изменён."
            )
          }}
        />
      ) : null}
      {availabilityGroup ? (
        <GroupAvailabilityDialog
          key={availabilityGroup.id}
          group={availabilityGroup}
          pending={mutation.isPending}
          error={actionError}
          onClose={closeEditors}
          onSave={async (reason) => {
            if (!accessToken) return
            await run(
              () =>
                taskBoardSettingsClient.disableGroup(
                  accessToken,
                  warehouseId,
                  availabilityGroup.id,
                  availabilityGroup.version,
                  reason
                ),
              "Бригада отключена.",
              false
            )
          }}
        />
      ) : null}
      {deleteTarget ? (
        <SettingsDeleteDialog
          title={
            deleteTarget.kind === "credentials"
              ? "Отключить учётные данные?"
              : "Удалить запись?"
          }
          description={
            deleteTarget.kind === "credentials"
              ? "Рабочий больше не сможет входить в приложение. Профиль и история сохранятся."
              : deleteTarget.kind === "queue-definition"
                ? "Используемую очередь удалить нельзя: сервис вернёт конфликт, пока остаются ссылки каталога."
                : "Если запись уже используется, API отклонит удаление и предложит деактивацию."
          }
          confirmLabel={
            deleteTarget.kind === "credentials" ? "Отключить" : "Удалить"
          }
          error={actionError}
          pending={mutation.isPending}
          onClose={closeEditors}
          onConfirm={confirmDelete}
        />
      ) : null}
    </div>
  )
}
