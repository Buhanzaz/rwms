import { useMemo, useState, type FormEvent } from "react"

import type { WarehouseInfo } from "@/api/warehouse-api"
import { TimeZoneSelect } from "@/components/time-zone-select"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import {
  Field,
  FieldContent,
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
  FieldLegend,
  FieldSet,
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
import type {
  UserGlobalRole,
  WarehouseAccessLevel,
} from "@/features/auth/auth-model"
import type {
  AdminUser,
  AdminUserProfileInput,
  AdminUserWarehouseAccess,
  CreateAdminUserInput,
} from "@/features/settings/users/model/users"
import {
  isManagerAppEligibleRole,
  userGlobalRoleLabels,
  warehouseAccessLevelLabels,
} from "@/features/settings/users/model/users"

const ACCESS_LEVELS = Object.keys(
  warehouseAccessLevelLabels
) as WarehouseAccessLevel[]

type UserEditorResult = {
  profile: AdminUserProfileInput | CreateAdminUserInput
  accesses: AdminUserWarehouseAccess[]
  password: string | null
}

type AccessDraft = {
  enabled: boolean
  accessLevel: WarehouseAccessLevel
  comment: string
}

function emptyAccessDraft(): AccessDraft {
  return { enabled: false, accessLevel: "VIEW", comment: "" }
}

export function UserEditorDialog({
  open,
  user,
  warehouses,
  pending,
  serverError,
  deactivationBlockedReason,
  allowedRoles,
  onOpenChange,
  onSubmit,
}: {
  open: boolean
  user: AdminUser | null
  warehouses: WarehouseInfo[]
  pending: boolean
  serverError: string | null
  deactivationBlockedReason: string | null
  allowedRoles: UserGlobalRole[]
  onOpenChange: (open: boolean) => void
  onSubmit: (result: UserEditorResult) => Promise<void>
}) {
  const [username, setUsername] = useState(user?.username ?? "")
  const [password, setPassword] = useState("")
  const [confirmPassword, setConfirmPassword] = useState("")
  const [firstName, setFirstName] = useState(user?.firstName ?? "")
  const [lastName, setLastName] = useState(user?.lastName ?? "")
  const [email, setEmail] = useState(user?.email ?? "")
  const [timeZoneId, setTimeZoneId] = useState(
    user?.timeZoneId ?? "Europe/Moscow"
  )
  const [globalRole, setGlobalRole] = useState<UserGlobalRole>(
    user?.globalRole ??
      (allowedRoles.includes("VIEWER")
        ? "VIEWER"
        : (allowedRoles[0] ?? "VIEWER"))
  )
  const [active, setActive] = useState(user?.active ?? true)
  const [mobileAppAccess, setMobileAppAccess] = useState(
    user?.mobileAppAccess ?? false
  )
  const [rentalAccess, setRentalAccess] = useState(user?.rentalAccess ?? false)
  const [accesses, setAccesses] = useState<Record<string, AccessDraft>>(() =>
    Object.fromEntries(
      warehouses.map((warehouse) => {
        const current = user?.warehouseAccesses.find(
          (access) => access.warehouseId === warehouse.id && access.active
        )

        return [
          warehouse.id,
          current
            ? {
                enabled: true,
                accessLevel: current.accessLevel,
                comment: current.comment ?? "",
              }
            : emptyAccessDraft(),
        ]
      })
    )
  )
  const [validationError, setValidationError] = useState<string | null>(null)

  const title = user === null ? "Новый пользователь" : "Пользователь"
  const managerAppEligible = isManagerAppEligibleRole(globalRole)

  const enabledAccessCount = useMemo(
    () => Object.values(accesses).filter((access) => access.enabled).length,
    [accesses]
  )

  function updateAccess(warehouseId: string, patch: Partial<AccessDraft>) {
    setAccesses((current) => ({
      ...current,
      [warehouseId]: {
        ...(current[warehouseId] ?? emptyAccessDraft()),
        ...patch,
      },
    }))
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setValidationError(null)

    if (!username.trim()) {
      setValidationError("Укажите логин.")
      return
    }

    if (user === null && password.length < 8) {
      setValidationError("Пароль должен содержать не менее 8 символов.")
      return
    }

    if (user === null && password !== confirmPassword) {
      setValidationError("Пароли не совпадают.")
      return
    }

    if (user !== null && password.length > 0 && password.length < 8) {
      setValidationError("Новый пароль должен содержать не менее 8 символов.")
      return
    }

    if (user !== null && password.length > 0 && password !== confirmPassword) {
      setValidationError("Пароли не совпадают.")
      return
    }

    if (!active && deactivationBlockedReason !== null) {
      setValidationError(deactivationBlockedReason)
      return
    }

    const profile: AdminUserProfileInput = {
      username: username.trim(),
      firstName: firstName.trim() || null,
      lastName: lastName.trim() || null,
      email: email.trim() || null,
      timeZoneId: timeZoneId,
      active,
      globalRole,
      mobileAppAccess: managerAppEligible ? mobileAppAccess : false,
      rentalAccess,
    }

    const warehouseAccesses =
      managerAppEligible && mobileAppAccess
        ? warehouses.flatMap((warehouse) => {
            const draft = accesses[warehouse.id]

            if (!draft?.enabled) {
              return []
            }

            return [
              {
                warehouseId: warehouse.id,
                accessLevel: draft.accessLevel,
                comment: draft.comment.trim() || null,
                active: true,
              },
            ]
          })
        : []

    await onSubmit({
      profile:
        user === null ? { ...profile, password, warehouseAccesses } : profile,
      accesses: warehouseAccesses,
      password: user === null || password.length === 0 ? null : password,
    })
  }

  return (
    <Dialog open={open} onOpenChange={(next) => !pending && onOpenChange(next)}>
      <DialogContent className="max-h-[calc(100svh-2rem)] overflow-y-auto sm:max-w-3xl">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          <DialogDescription>
            Профиль, глобальная роль и доступы.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={handleSubmit} className="flex flex-col gap-6">
          <FieldGroup className="grid gap-4 md:grid-cols-2">
            <Field data-invalid={validationError !== null && !username.trim()}>
              <FieldLabel htmlFor="user-username">Логин</FieldLabel>
              <Input
                id="user-username"
                value={username}
                onChange={(event) => setUsername(event.target.value)}
                readOnly={user !== null}
                autoComplete="off"
                aria-invalid={validationError !== null && !username.trim()}
              />
            </Field>

            <Field>
              <FieldLabel htmlFor="user-global-role">
                Глобальная роль
              </FieldLabel>
              <Select
                value={globalRole}
                onValueChange={(value) => {
                  const role = value as UserGlobalRole
                  setGlobalRole(role)
                  if (!isManagerAppEligibleRole(role)) {
                    setMobileAppAccess(false)
                  }
                }}
              >
                <SelectTrigger id="user-global-role" className="w-full">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectGroup>
                    {allowedRoles.map((role) => (
                      <SelectItem key={role} value={role}>
                        {userGlobalRoleLabels[role]}
                      </SelectItem>
                    ))}
                  </SelectGroup>
                </SelectContent>
              </Select>
            </Field>

            <Field>
              <FieldLabel htmlFor="user-first-name">Имя</FieldLabel>
              <Input
                id="user-first-name"
                value={firstName}
                onChange={(event) => setFirstName(event.target.value)}
                autoComplete="given-name"
              />
            </Field>

            <Field>
              <FieldLabel htmlFor="user-last-name">Фамилия</FieldLabel>
              <Input
                id="user-last-name"
                value={lastName}
                onChange={(event) => setLastName(event.target.value)}
                autoComplete="family-name"
              />
            </Field>

            <Field>
              <FieldLabel htmlFor="user-email">Email</FieldLabel>
              <Input
                id="user-email"
                type="email"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                autoComplete="email"
              />
            </Field>

            <Field>
              <FieldLabel htmlFor="user-time-zone">Временная зона</FieldLabel>
              <TimeZoneSelect
                id="user-time-zone"
                value={timeZoneId}
                onValueChange={setTimeZoneId}
                className="w-full"
              />
            </Field>

            {user === null ? (
              <>
                <Field data-invalid={validationError !== null && !password}>
                  <FieldLabel htmlFor="user-password">Пароль</FieldLabel>
                  <Input
                    id="user-password"
                    type="password"
                    value={password}
                    onChange={(event) => setPassword(event.target.value)}
                    autoComplete="new-password"
                    aria-invalid={validationError !== null && !password}
                  />
                </Field>

                <Field
                  data-invalid={
                    validationError !== null && password !== confirmPassword
                  }
                >
                  <FieldLabel htmlFor="user-confirm-password">
                    Повторите пароль
                  </FieldLabel>
                  <Input
                    id="user-confirm-password"
                    type="password"
                    value={confirmPassword}
                    onChange={(event) => setConfirmPassword(event.target.value)}
                    autoComplete="new-password"
                    aria-invalid={
                      validationError !== null && password !== confirmPassword
                    }
                  />
                </Field>
              </>
            ) : (
              <>
                <Field
                  data-invalid={validationError !== null && password.length > 0}
                >
                  <FieldLabel htmlFor="user-password">Новый пароль</FieldLabel>
                  <Input
                    id="user-password"
                    type="password"
                    value={password}
                    onChange={(event) => setPassword(event.target.value)}
                    autoComplete="new-password"
                    placeholder="Необязательно"
                    aria-invalid={
                      validationError !== null && password.length > 0
                    }
                  />
                </Field>

                <Field
                  data-invalid={
                    validationError !== null &&
                    password.length > 0 &&
                    password !== confirmPassword
                  }
                >
                  <FieldLabel htmlFor="user-confirm-password">
                    Повторите новый пароль
                  </FieldLabel>
                  <Input
                    id="user-confirm-password"
                    type="password"
                    value={confirmPassword}
                    onChange={(event) => setConfirmPassword(event.target.value)}
                    autoComplete="new-password"
                    disabled={password.length === 0}
                    aria-invalid={
                      validationError !== null &&
                      password.length > 0 &&
                      password !== confirmPassword
                    }
                  />
                </Field>
              </>
            )}

            <Field orientation="horizontal" className="md:col-span-2">
              <Checkbox
                id="user-active"
                checked={active}
                onCheckedChange={(checked) => setActive(checked === true)}
                disabled={active && deactivationBlockedReason !== null}
              />
              <FieldLabel htmlFor="user-active">Активен</FieldLabel>
              {active && deactivationBlockedReason !== null ? (
                <FieldDescription>{deactivationBlockedReason}</FieldDescription>
              ) : null}
            </Field>
          </FieldGroup>

          <FieldSet className="rounded-lg border p-4">
            <FieldLegend>Доступы</FieldLegend>
            <FieldGroup data-slot="checkbox-group" className="gap-3">
              <Field orientation="horizontal">
                <Checkbox
                  id="user-mobile-app-access"
                  checked={managerAppEligible && mobileAppAccess}
                  onCheckedChange={(checked) =>
                    setMobileAppAccess(checked === true)
                  }
                  disabled={!managerAppEligible}
                />
                <FieldContent>
                  <FieldLabel htmlFor="user-mobile-app-access">
                    Доступ к RWMS
                  </FieldLabel>
                  <FieldDescription>
                    Доступ к приложению руководителя. Доступ разрешён только
                    системному администратору, администратору WMS и руководителю
                    склада.
                  </FieldDescription>
                </FieldContent>
              </Field>
              <Field orientation="horizontal">
                <Checkbox
                  id="user-rental-access"
                  checked={rentalAccess}
                  onCheckedChange={(checked) =>
                    setRentalAccess(checked === true)
                  }
                />
                <FieldContent>
                  <FieldLabel htmlFor="user-rental-access">
                    Доступ к аренде
                  </FieldLabel>
                </FieldContent>
              </Field>
            </FieldGroup>
          </FieldSet>

          {managerAppEligible && mobileAppAccess ? (
            <FieldSet className="rounded-lg border p-4">
              <FieldLegend>
                Доступ к объектам ({enabledAccessCount})
              </FieldLegend>
              <FieldGroup className="gap-3">
                {warehouses.map((warehouse) => {
                  const draft = accesses[warehouse.id] ?? emptyAccessDraft()

                  return (
                    <Field key={warehouse.id} className="rounded-lg border p-3">
                      <div className="flex items-center gap-3">
                        <Checkbox
                          id={`warehouse-access-${warehouse.id}`}
                          checked={draft.enabled}
                          onCheckedChange={(checked) =>
                            updateAccess(warehouse.id, {
                              enabled: checked === true,
                            })
                          }
                        />
                        <FieldLabel
                          htmlFor={`warehouse-access-${warehouse.id}`}
                        >
                          {warehouse.name}
                        </FieldLabel>
                      </div>

                      {draft.enabled ? (
                        <div className="grid gap-3 md:grid-cols-2">
                          <Select
                            value={draft.accessLevel}
                            onValueChange={(value) =>
                              updateAccess(warehouse.id, {
                                accessLevel: value as WarehouseAccessLevel,
                              })
                            }
                          >
                            <SelectTrigger
                              aria-label={`Уровень доступа к объекту: ${warehouse.name}`}
                              className="w-full"
                            >
                              <SelectValue />
                            </SelectTrigger>
                            <SelectContent>
                              <SelectGroup>
                                {ACCESS_LEVELS.map((level) => (
                                  <SelectItem key={level} value={level}>
                                    {warehouseAccessLevelLabels[level]}
                                  </SelectItem>
                                ))}
                              </SelectGroup>
                            </SelectContent>
                          </Select>

                          <Input
                            value={draft.comment}
                            onChange={(event) =>
                              updateAccess(warehouse.id, {
                                comment: event.target.value,
                              })
                            }
                            aria-label={`Комментарий доступа к объекту: ${warehouse.name}`}
                            placeholder="Комментарий"
                          />
                        </div>
                      ) : null}
                    </Field>
                  )
                })}
              </FieldGroup>
            </FieldSet>
          ) : null}

          {validationError || serverError ? (
            <FieldError>{validationError ?? serverError}</FieldError>
          ) : null}

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={pending}
              onClick={() => onOpenChange(false)}
            >
              Отмена
            </Button>
            <Button type="submit" disabled={pending}>
              {pending ? "Сохраняем…" : "Сохранить"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
