import { useRef, useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { toast } from "sonner"

import { OperationsListGrid } from "@/components/operations-list-grid"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { isGlobalAdministrator } from "@/features/auth/auth-model"
import { useAuth } from "@/features/auth/use-auth"
import {
  taskBoardSettingsClient,
  taskBoardSettingsKeys,
} from "@/features/settings/task-board/api/task-board-settings-api"
import type {
  WorkerClassDto,
  WorkerClassRequest,
  WorkerDto,
  WorkerGroupDto,
  WorkerGroupRequest,
  WorkerRequest,
  WorkQueueDto,
  WorkQueueRequest,
} from "@/features/settings/task-board/model/task-board-settings"
import {
  credentialStatusLabels,
  queueTypeLabels,
} from "@/features/settings/task-board/model/task-board-settings"
import { QueueOrderSettings } from "@/features/settings/task-board/queue-order-settings"
import {
  ClassEditorDialog,
  CredentialPasswordDialog,
  GroupEditorDialog,
  QueueEditorDialog,
  WorkerEditorDialog,
} from "@/features/settings/task-board/settings-editor-dialogs"
import { SettingsDeleteDialog } from "@/features/settings/task-board/settings-delete-dialog"
import {
  isTaskBoardSettingsConflict as isConflict,
  taskBoardSettingsErrorMessage as message,
} from "@/features/settings/task-board/task-board-settings-errors"
import { useWarehouse } from "@/hooks/use-warehouse"

type DeleteTarget =
  | { kind: "queue"; item: WorkQueueDto }
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

export function TaskBoardSettingsPage() {
  const queryClient = useQueryClient()
  const { accessToken, currentUser } = useAuth()
  const { selectedWarehouse } = useWarehouse()
  const warehouseId = selectedWarehouse?.serviceId ?? ""
  const [queueEditor, setQueueEditor] = useState<WorkQueueDto | "new" | null>(
    null
  )
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
  const [deleteTarget, setDeleteTarget] = useState<DeleteTarget | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [bootstrapSummary, setBootstrapSummary] = useState<string | null>(null)
  const bootstrapIdempotencyKey = useRef<string | null>(null)

  const explicitAccess = currentUser?.warehouseAccesses.find(
    (access) => access.warehouseId === warehouseId
  )
  const canManage = Boolean(
    currentUser?.warehouseAccessAll || explicitAccess?.level === "MANAGE"
  )
  const canManageGlobal = Boolean(
    currentUser && isGlobalAdministrator(currentUser.globalRole)
  )

  const queuesQuery = useQuery({
    queryKey: taskBoardSettingsKeys.queues(warehouseId),
    queryFn: () =>
      taskBoardSettingsClient.listQueues(accessToken!, warehouseId),
    enabled: Boolean(accessToken && warehouseId && canManage),
  })
  const classesQuery = useQuery({
    queryKey: taskBoardSettingsKeys.classes,
    queryFn: () => taskBoardSettingsClient.listClasses(accessToken!),
    enabled: Boolean(accessToken && canManage),
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
    setQueueEditor(null)
    setClassEditor(null)
    setWorkerEditor(null)
    setGroupEditor(null)
    setPasswordWorker(null)
    setDeleteTarget(null)
    setActionError(null)
  }

  async function invalidateSettings() {
    await Promise.all([
      queryClient.invalidateQueries({
        queryKey: taskBoardSettingsKeys.classes,
      }),
      queryClient.invalidateQueries({
        queryKey: taskBoardSettingsKeys.queues(warehouseId),
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
      await invalidateSettings()
      closeEditors()
      toast.success(command.success)
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
      await invalidateSettings()
    },
  })
  const bootstrapMutation = useMutation({
    mutationFn: () => {
      if (!accessToken || !warehouseId || !canManage) {
        throw new Error(
          "Для загрузки данных старой панели нужен MANAGE выбранного склада."
        )
      }
      const idempotencyKey =
        bootstrapIdempotencyKey.current ?? crypto.randomUUID()
      bootstrapIdempotencyKey.current = idempotencyKey
      return taskBoardSettingsClient.bootstrapReviewedData(
        accessToken,
        warehouseId,
        idempotencyKey
      )
    },
    onSuccess: async (result) => {
      bootstrapIdempotencyKey.current = null
      setActionError(null)
      setBootstrapSummary(
        `Очереди: ${result.counts.workQueues}, классы: ${result.counts.workerClasses}, рабочие: ${result.counts.workers}, бригады: ${result.counts.workerGroups}.`
      )
      await invalidateSettings()
      toast.success(
        result.created > 0
          ? "Проверенные данные старой панели загружены."
          : "Проверенные данные уже были загружены; дубли не созданы."
      )
    },
    onError: async (error) => {
      const text = message(error)
      setActionError(text)
      toast.error(text)
      await invalidateSettings()
    },
  })

  async function run(
    execute: () => Promise<unknown>,
    success: string,
    closeOnConflict = true
  ) {
    setActionError(null)
    try {
      await mutation.mutateAsync({ execute, success, closeOnConflict })
    } catch {
      // onError owns the visible ProblemDetail/409 presentation and refetch.
    }
  }

  if (!selectedWarehouse) {
    return (
      <Card size="sm">
        <CardContent className="text-sm text-muted-foreground">
          Выберите склад.
        </CardContent>
      </Card>
    )
  }
  if (!canManage) {
    return (
      <Card size="sm">
        <CardContent className="text-sm text-muted-foreground">
          Для настройки доски нужен уровень MANAGE выбранного склада.
        </CardContent>
      </Card>
    )
  }

  const queryError =
    queuesQuery.error ??
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
    queuesQuery.isLoading ||
    classesQuery.isLoading ||
    workersQuery.isLoading ||
    groupsQuery.isLoading
  ) {
    return (
      <p className="text-sm text-muted-foreground">Загрузка настроек доски…</p>
    )
  }

  const queues = queuesQuery.data ?? []
  const classes = classesQuery.data ?? []
  const workers = workersQuery.data ?? []
  const groups = groupsQuery.data ?? []
  const actions = (edit: () => void, remove: () => void) => (
    <div className="flex items-center gap-2">
      <Button type="button" size="sm" variant="outline" onClick={edit}>
        Изменить
      </Button>
      <Button type="button" size="sm" variant="ghost" onClick={remove}>
        Удалить
      </Button>
    </div>
  )

  async function confirmDelete() {
    if (!deleteTarget || !accessToken) return
    const target = deleteTarget
    switch (target.kind) {
      case "queue":
        return run(
          () =>
            taskBoardSettingsClient.deleteQueue(
              accessToken,
              warehouseId,
              target.item.id,
              target.item.version
            ),
          "Очередь удалена."
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

  return (
    <div className="flex h-full min-h-0 flex-col gap-4">
      <Card size="sm">
        <CardContent className="flex flex-wrap items-center justify-between gap-3">
          <div className="min-w-0">
            <p className="font-medium">Проверенные данные старой панели</p>
            <p className="text-sm text-muted-foreground">
              Загружает очереди, классы, рабочих, квалификации и бригады в
              task-board PostgreSQL. Повторный запуск не создаёт дубли.
            </p>
            {bootstrapSummary ? (
              <p role="status" className="mt-1 text-sm text-muted-foreground">
                {bootstrapSummary}
              </p>
            ) : null}
          </div>
          <Button
            type="button"
            variant="outline"
            disabled={bootstrapMutation.isPending}
            onClick={() => bootstrapMutation.mutate()}
          >
            {bootstrapMutation.isPending
              ? "Загрузка…"
              : "Загрузить данные старой панели"}
          </Button>
        </CardContent>
      </Card>

      <Tabs defaultValue="queues" className="flex min-h-0 flex-1 flex-col">
        <TabsList
          className="w-full justify-start overflow-x-auto"
          variant="line"
        >
          <TabsTrigger value="queues">Очереди</TabsTrigger>
          <TabsTrigger value="order">Порядок</TabsTrigger>
          <TabsTrigger value="classes">Классы</TabsTrigger>
          <TabsTrigger value="groups">Бригады</TabsTrigger>
          <TabsTrigger value="workers">Рабочие</TabsTrigger>
        </TabsList>

        <TabsContent
          value="queues"
          className="flex min-h-0 flex-1 flex-col gap-3"
        >
          <div>
            <Button onClick={() => setQueueEditor("new")}>
              Создать очередь
            </Button>
          </div>
          <OperationsListGrid
            className="min-h-0 flex-1 overflow-auto"
            items={queues}
            columns={[
              {
                id: "code",
                label: "Код",
                getSortValue: (q) => q.code,
                render: (q) => q.code,
              },
              {
                id: "name",
                label: "Название",
                getSortValue: (q) => q.name,
                render: (q) => q.name,
              },
              {
                id: "type",
                label: "Тип",
                getSortValue: (q) => queueTypeLabels[q.type],
                render: (q) => queueTypeLabels[q.type],
              },
              {
                id: "classes",
                label: "Классы",
                getSortValue: (q) => q.bindings.length,
                render: (q) =>
                  q.bindings.map((b) => b.workerClass.name).join(", ") || "Все",
              },
              {
                id: "status",
                label: "Статус",
                getSortValue: (q) => (q.active ? 1 : 0),
                render: (q) => <StatusBadge active={q.active} />,
              },
              {
                id: "actions",
                label: "Действия",
                getSortValue: () => null,
                render: (q) =>
                  actions(
                    () => setQueueEditor(q),
                    () => setDeleteTarget({ kind: "queue", item: q })
                  ),
              },
            ]}
          />
        </TabsContent>

        <TabsContent value="order" className="flex min-h-0 flex-1">
          <QueueOrderSettings
            key={queues.map((q) => `${q.id}:${q.version}`).join("|")}
            queues={queues}
            pending={mutation.isPending}
            onSave={async (ordered) => {
              if (!accessToken) return
              await run(
                () =>
                  taskBoardSettingsClient.reorderQueues(
                    accessToken,
                    warehouseId,
                    ordered.map((queue) => ({
                      queueId: queue.id,
                      expectedVersion: queue.version,
                    }))
                  ),
                "Порядок очередей сохранён."
              )
            }}
          />
        </TabsContent>

        <TabsContent
          value="classes"
          className="flex min-h-0 flex-1 flex-col gap-3"
        >
          {canManageGlobal ? (
            <div>
              <Button onClick={() => setClassEditor("new")}>
                Создать класс
              </Button>
            </div>
          ) : null}
          <OperationsListGrid
            className="min-h-0 flex-1 overflow-auto"
            items={classes}
            columns={[
              {
                id: "code",
                label: "Код",
                getSortValue: (item) => item.code,
                render: (item) => item.code,
              },
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
                  canManageGlobal
                    ? actions(
                        () => setClassEditor(item),
                        () => setDeleteTarget({ kind: "class", item })
                      )
                    : "Только просмотр",
              },
            ]}
          />
        </TabsContent>

        <TabsContent
          value="groups"
          className="flex min-h-0 flex-1 flex-col gap-3"
        >
          <div>
            <Button onClick={() => setGroupEditor("new")}>
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
                id: "actions",
                label: "Действия",
                getSortValue: () => null,
                render: (item) =>
                  actions(
                    () => setGroupEditor(item),
                    () => setDeleteTarget({ kind: "group", item })
                  ),
              },
            ]}
          />
        </TabsContent>

        <TabsContent
          value="workers"
          className="flex min-h-0 flex-1 flex-col gap-3"
        >
          <div>
            <Button onClick={() => setWorkerEditor("new")}>
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
                    .filter((q) => q.active)
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
                cellClassName: "w-[22rem]",
                render: (item) => (
                  <div className="flex items-center gap-2">
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => setWorkerEditor(item)}
                    >
                      Изменить
                    </Button>
                    {item.appLogin ? (
                      <>
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() => setPasswordWorker(item)}
                        >
                          Пароль
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          onClick={() =>
                            setDeleteTarget({ kind: "credentials", item })
                          }
                        >
                          Отключить вход
                        </Button>
                      </>
                    ) : null}
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => setDeleteTarget({ kind: "worker", item })}
                    >
                      Удалить
                    </Button>
                  </div>
                ),
              },
            ]}
          />
        </TabsContent>
      </Tabs>

      {queueEditor ? (
        <QueueEditorDialog
          key={queueEditor === "new" ? "new" : queueEditor.id}
          queue={queueEditor === "new" ? null : queueEditor}
          classes={classes}
          pending={mutation.isPending}
          error={actionError}
          onClose={closeEditors}
          onSave={async (request: WorkQueueRequest) => {
            if (!accessToken) return
            await run(
              () =>
                queueEditor === "new"
                  ? taskBoardSettingsClient.createQueue(
                      accessToken,
                      warehouseId,
                      request
                    )
                  : taskBoardSettingsClient.updateQueue(
                      accessToken,
                      warehouseId,
                      queueEditor.id,
                      request
                    ),
              "Очередь сохранена.",
              queueEditor !== "new"
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
