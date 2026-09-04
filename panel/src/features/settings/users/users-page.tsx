import { useMemo, useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { FilterIcon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { toast } from "sonner"

import type { WarehouseInfo } from "@/api/warehouse-api"
import { OperationsListGrid } from "@/components/operations-list-grid"
import {
  PageToolbar,
  PageToolbarActions,
  PageToolbarContent,
} from "@/components/page-toolbar"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import type { UserGlobalRole } from "@/features/auth/auth-model"
import { useAuth } from "@/features/auth/use-auth"
import {
  changeAdminUserPassword,
  createAdminUser,
  listAdminUsers,
  replaceAdminUserWarehouseAccesses,
  updateAdminUser,
} from "@/features/settings/users/api/users-api"
import {
  getAdminUserDisplayName,
  type AdminUser,
  type AdminUserProfileInput,
  type AdminUserWarehouseAccess,
  type CreateAdminUserInput,
  userGlobalRoleLabels,
} from "@/features/settings/users/model/users"
import { UserFilters } from "@/features/settings/users/user-filters"
import {
  EMPTY_ADMIN_USER_FILTERS,
  buildAdminUserFilterOptions,
  filterAdminUsers,
  type AdminUserFilters,
} from "@/features/settings/users/user-filtering"
import { UserEditorDialog } from "@/features/settings/users/user-editor-dialog"
import { useResponsiveFiltersOpen } from "@/hooks/use-responsive-filters-open"
import { useWarehouse } from "@/hooks/use-warehouse"
import { ApiError } from "@/lib/api-client"

function isConflict(error: unknown): error is ApiError {
  return error instanceof ApiError && error.status === 409
}

function getErrorMessage(error: unknown, staleSelection = false) {
  if (staleSelection && isConflict(error)) {
    return `${error.message} Данные обновлены с сервера. Откройте пользователя заново.`
  }
  return error instanceof Error ? error.message : "Операция не выполнена."
}

function userAccessLabels(
  user: AdminUser,
  warehousesById: ReadonlyMap<string, WarehouseInfo>
) {
  const labels: string[] = []
  if (user.mobileAppAccess) labels.push("Приложение")
  if (user.rentalAccess) labels.push("Аренда")

  for (const access of user.warehouseAccesses) {
    if (!access.active) continue
    labels.push(
      ["Объект:", warehousesById.get(access.warehouseId)?.name ?? "—"].join(" ")
    )
  }

  return labels
}

function UserAccessSummary({
  user,
  warehousesById,
}: {
  user: AdminUser
  warehousesById: ReadonlyMap<string, WarehouseInfo>
}) {
  const labels = userAccessLabels(user, warehousesById)
  if (labels.length === 0)
    return <span className="text-muted-foreground">—</span>

  return (
    <div className="flex flex-wrap gap-1">
      {labels.map((label) => (
        <Badge key={label} variant="outline">
          {label}
        </Badge>
      ))}
    </div>
  )
}

export function UsersPage() {
  const queryClient = useQueryClient()
  const { accessToken, currentUser } = useAuth()
  const { warehouses } = useWarehouse()
  const [search, setSearch] = useState("")
  const [filters, setFilters] = useState<AdminUserFilters>(
    EMPTY_ADMIN_USER_FILTERS
  )
  const { filtersOpen, setFiltersOpen } = useResponsiveFiltersOpen()
  const [editorOpen, setEditorOpen] = useState(false)
  const [editedUser, setEditedUser] = useState<AdminUser | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const canManageUsers = currentUser?.globalRole === "SYSTEM_ADMIN"
  const assignableRoles = (
    Object.keys(userGlobalRoleLabels) as UserGlobalRole[]
  ).filter((role) => role !== "WMS_ADMIN")
  const allowedRoles =
    editedUser?.globalRole === "WMS_ADMIN"
      ? (["WMS_ADMIN", ...assignableRoles] as UserGlobalRole[])
      : assignableRoles

  const usersQuery = useQuery({
    queryKey: ["admin-users"],
    queryFn: () => listAdminUsers(accessToken!),
    enabled: accessToken !== null && canManageUsers,
  })
  const saveMutation = useMutation({
    mutationFn: async ({
      profile,
      accesses,
      password,
    }: {
      profile: AdminUserProfileInput | CreateAdminUserInput
      accesses: AdminUserWarehouseAccess[]
      password: string | null
    }) => {
      if (accessToken === null || currentUser === null) {
        throw new Error("Сессия завершена.")
      }

      if (editedUser === null) {
        const createInput = profile as CreateAdminUserInput
        return createAdminUser(accessToken, {
          ...createInput,
          warehouseAccesses: accesses,
        })
      }

      const user = await updateAdminUser(
        accessToken,
        editedUser.id,
        editedUser.version,
        profile as AdminUserProfileInput
      )

      const userWithAccesses = await replaceAdminUserWarehouseAccesses(
        accessToken,
        user.id,
        user.version,
        { accesses }
      )

      if (password !== null) {
        await changeAdminUserPassword(
          accessToken,
          userWithAccesses.id,
          userWithAccesses.version,
          password
        )
      }

      return userWithAccesses
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["admin-users"] })
      setEditorOpen(false)
      setEditedUser(null)
      setActionError(null)
      toast.success("Пользователь сохранён.")
    },
    onError: async (error) => {
      const staleSelection = editedUser !== null && isConflict(error)
      const message = getErrorMessage(error, staleSelection)

      if (staleSelection) {
        setEditorOpen(false)
        setEditedUser(null)
        setActionError(null)
      } else {
        setActionError(message)
      }

      toast.error(message)
      await queryClient.invalidateQueries({ queryKey: ["admin-users"] })
    },
  })

  const visibleUsers = usersQuery.data ?? []
  const filteredUsers = useMemo(() => {
    return filterAdminUsers(visibleUsers, search, filters)
  }, [filters, search, visibleUsers])
  const filterOptions = useMemo(
    () => buildAdminUserFilterOptions(visibleUsers),
    [visibleUsers]
  )
  const warehousesById = useMemo(
    () => new Map(warehouses.map((warehouse) => [warehouse.id, warehouse])),
    [warehouses]
  )

  const activeSystemAdministrators = visibleUsers.filter(
    (user) => user.active && user.globalRole === "SYSTEM_ADMIN"
  )
  const deactivationBlockedReason =
    editedUser?.active && editedUser.id === currentUser?.id
      ? "Нельзя отключить текущего пользователя."
      : editedUser?.active &&
          editedUser.globalRole === "SYSTEM_ADMIN" &&
          activeSystemAdministrators.length <= 1
        ? "Нельзя отключить последнего активного системного администратора."
        : null

  if (!canManageUsers) {
    return (
      <Card size="sm">
        <CardContent className="text-sm text-muted-foreground">
          Управление пользователями доступно только администраторам.
        </CardContent>
      </Card>
    )
  }

  function openCreateDialog() {
    setEditedUser(null)
    setActionError(null)
    setEditorOpen(true)
  }

  function openEditDialog(user: AdminUser) {
    setEditedUser(user)
    setActionError(null)
    setEditorOpen(true)
  }

  return (
    <div className="flex h-full min-h-0 flex-col gap-4">
      <PageToolbar>
        <PageToolbarContent className="max-w-xl">
          <Input
            type="search"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="Логин, имя или email"
            aria-label="Поиск пользователей"
          />
        </PageToolbarContent>
        <PageToolbarActions className="w-full sm:w-auto">
          <Button
            type="button"
            size="icon"
            variant={filtersOpen ? "secondary" : "outline"}
            aria-label={
              filtersOpen
                ? "Скрыть фильтры пользователей"
                : "Показать фильтры пользователей"
            }
            aria-controls="users-filters"
            aria-expanded={filtersOpen}
            onClick={() => setFiltersOpen((current) => !current)}
          >
            <HugeiconsIcon icon={FilterIcon} aria-hidden="true" />
          </Button>
          <Button type="button" onClick={openCreateDialog}>
            Создать пользователя
          </Button>
        </PageToolbarActions>
      </PageToolbar>

      <div id="users-filters" hidden={!filtersOpen}>
        <UserFilters
          filters={filters}
          options={filterOptions}
          onChange={setFilters}
        />
      </div>

      {usersQuery.isLoading ? (
        <p className="text-sm text-muted-foreground">Загрузка пользователей…</p>
      ) : usersQuery.isError ? (
        <Card size="sm">
          <CardContent className="flex flex-col gap-3 text-sm text-destructive">
            <p role="alert">{getErrorMessage(usersQuery.error)}</p>
            <Button
              type="button"
              variant="outline"
              onClick={() => void usersQuery.refetch()}
            >
              Повторить
            </Button>
          </CardContent>
        </Card>
      ) : (
        <>
          <OperationsListGrid
            className="hidden min-h-0 flex-1 overflow-auto md:block"
            items={filteredUsers}
            columns={[
              {
                id: "username",
                label: "Логин",
                getSortValue: (user) => user.username,
                render: (user) => user.username,
              },
              {
                id: "name",
                label: "Имя",
                getSortValue: getAdminUserDisplayName,
                render: getAdminUserDisplayName,
              },
              {
                id: "email",
                label: "Email",
                getSortValue: (user) => user.email,
                render: (user) => user.email ?? "—",
              },
              {
                id: "role",
                label: "Роль",
                getSortValue: (user) => userGlobalRoleLabels[user.globalRole],
                render: (user) => userGlobalRoleLabels[user.globalRole],
              },
              {
                id: "status",
                label: "Статус",
                getSortValue: (user) => (user.active ? 1 : 0),
                render: (user) => (
                  <Badge variant={user.active ? "secondary" : "outline"}>
                    {user.active ? "Активен" : "Отключён"}
                  </Badge>
                ),
              },
              {
                id: "accesses",
                label: "Доступы",
                getSortValue: (user) =>
                  userAccessLabels(user, warehousesById).join(" "),
                render: (user) => (
                  <UserAccessSummary
                    user={user}
                    warehousesById={warehousesById}
                  />
                ),
              },
              {
                id: "actions",
                label: "Действия",
                getSortValue: () => null,
                cellClassName: "w-28",
                render: (user) => (
                  <Button
                    type="button"
                    size="sm"
                    variant="outline"
                    onClick={() => openEditDialog(user)}
                  >
                    Изменить
                  </Button>
                ),
              },
            ]}
          />

          <div className="flex min-h-0 flex-col gap-3 overflow-y-auto md:hidden">
            {filteredUsers.map((user) => (
              <Card key={user.id} size="sm">
                <CardContent className="flex flex-col gap-3 text-sm">
                  <div className="flex items-start justify-between gap-3">
                    <div>
                      <p className="font-medium">
                        {getAdminUserDisplayName(user)}
                      </p>
                      <p className="text-muted-foreground">{user.username}</p>
                    </div>
                    <Badge variant={user.active ? "secondary" : "outline"}>
                      {user.active ? "Активен" : "Отключён"}
                    </Badge>
                  </div>
                  <p className="text-muted-foreground">
                    {userGlobalRoleLabels[user.globalRole]}
                  </p>
                  <div className="flex flex-col gap-1">
                    <p className="text-muted-foreground">Доступы</p>
                    <UserAccessSummary
                      user={user}
                      warehousesById={warehousesById}
                    />
                  </div>
                  <div className="flex gap-2">
                    <Button
                      type="button"
                      size="sm"
                      variant="outline"
                      onClick={() => openEditDialog(user)}
                    >
                      Изменить
                    </Button>
                  </div>
                </CardContent>
              </Card>
            ))}
          </div>
        </>
      )}

      {editorOpen ? (
        <UserEditorDialog
          key={editedUser?.id ?? "new"}
          open
          user={editedUser}
          warehouses={warehouses}
          pending={saveMutation.isPending}
          serverError={actionError}
          deactivationBlockedReason={deactivationBlockedReason}
          allowedRoles={allowedRoles}
          onOpenChange={(open) => {
            setEditorOpen(open)
            if (!open) {
              setEditedUser(null)
              setActionError(null)
            }
          }}
          onSubmit={async (result) => {
            await saveMutation.mutateAsync(result)
          }}
        />
      ) : null}
    </div>
  )
}
