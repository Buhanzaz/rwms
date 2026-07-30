import type { UserGlobalRole } from "@/features/auth/auth-model"
import {
  type AdminUser,
  userGlobalRoleLabels,
} from "@/features/settings/users/model/users"
import { smartLocalSearch } from "@/lib/smart-local-search"

export type AdminUserStatus = "ACTIVE" | "INACTIVE"
export type AdminUserMobileAppAccess = "ALLOWED" | "NOT_ALLOWED"

export type AdminUserFilterOption<T extends string> = {
  value: T
  label: string
}

export type AdminUserFilters = {
  logins: string[]
  names: string[]
  emails: string[]
  roles: UserGlobalRole[]
  statuses: AdminUserStatus[]
  mobileAppAccesses: AdminUserMobileAppAccess[]
}

export type AdminUserFilterOptions = {
  logins: AdminUserFilterOption<string>[]
  names: AdminUserFilterOption<string>[]
  emails: AdminUserFilterOption<string>[]
  roles: AdminUserFilterOption<UserGlobalRole>[]
  statuses: AdminUserFilterOption<AdminUserStatus>[]
  mobileAppAccesses: AdminUserFilterOption<AdminUserMobileAppAccess>[]
}

export const EMPTY_ADMIN_USER_FILTERS: AdminUserFilters = {
  logins: [],
  names: [],
  emails: [],
  roles: [],
  statuses: [],
  mobileAppAccesses: [],
}

const userStatusOptions: AdminUserFilterOption<AdminUserStatus>[] = [
  { value: "ACTIVE", label: "Активен" },
  { value: "INACTIVE", label: "Отключён" },
]

const mobileAppAccessOptions: AdminUserFilterOption<AdminUserMobileAppAccess>[] =
  [
    { value: "ALLOWED", label: "Доступ разрешён" },
    { value: "NOT_ALLOWED", label: "Нет доступа" },
  ]

function normalize(value: string | null | undefined) {
  return value?.trim().toLocaleLowerCase("ru-RU") ?? ""
}

function hasText(value: string | null | undefined) {
  return normalize(value) !== ""
}

function uniqueOptions<T extends string>(options: AdminUserFilterOption<T>[]) {
  return [
    ...new Map(options.map((option) => [option.value, option])).values(),
  ].sort((left, right) => left.label.localeCompare(right.label, "ru-RU"))
}

function userStatus(user: AdminUser): AdminUserStatus {
  return user.active ? "ACTIVE" : "INACTIVE"
}

function userMobileAppAccess(user: AdminUser): AdminUserMobileAppAccess {
  return user.mobileAppAccess ? "ALLOWED" : "NOT_ALLOWED"
}

export function getAdminUserFilterName(user: AdminUser) {
  const fullName = [user.lastName, user.firstName].filter(hasText).join(" ")
  return fullName || user.username
}

export function buildAdminUserFilterOptions(
  users: AdminUser[]
): AdminUserFilterOptions {
  return {
    logins: uniqueOptions(
      users.map((user) => ({ value: user.username, label: user.username }))
    ),
    names: uniqueOptions(
      users.map((user) => {
        const name = getAdminUserFilterName(user)
        return { value: name, label: name }
      })
    ),
    emails: uniqueOptions(
      users.flatMap((user) =>
        hasText(user.email) && user.email !== null
          ? [{ value: user.email, label: user.email }]
          : []
      )
    ),
    roles: uniqueOptions(
      users.map((user) => ({
        value: user.globalRole,
        label: userGlobalRoleLabels[user.globalRole],
      }))
    ),
    statuses: userStatusOptions,
    mobileAppAccesses: mobileAppAccessOptions,
  }
}

export function hasActiveAdminUserFilters(filters: AdminUserFilters) {
  return (
    filters.logins.length > 0 ||
    filters.names.length > 0 ||
    filters.emails.length > 0 ||
    filters.roles.length > 0 ||
    filters.statuses.length > 0 ||
    filters.mobileAppAccesses.length > 0
  )
}

export function filterAdminUsers(
  users: AdminUser[],
  search: string,
  filters: AdminUserFilters
) {
  const searchedUsers = smartLocalSearch(users, search, (user) => [
    user.username,
    user.firstName,
    user.lastName,
    user.email,
  ])

  return searchedUsers.filter((user) => {
    if (filters.logins.length > 0 && !filters.logins.includes(user.username)) {
      return false
    }
    if (
      filters.names.length > 0 &&
      !filters.names.includes(getAdminUserFilterName(user))
    ) {
      return false
    }
    if (
      filters.emails.length > 0 &&
      (user.email === null || !filters.emails.includes(user.email))
    ) {
      return false
    }
    if (filters.roles.length > 0 && !filters.roles.includes(user.globalRole)) {
      return false
    }
    if (
      filters.statuses.length > 0 &&
      !filters.statuses.includes(userStatus(user))
    ) {
      return false
    }
    if (
      filters.mobileAppAccesses.length > 0 &&
      !filters.mobileAppAccesses.includes(userMobileAppAccess(user))
    ) {
      return false
    }

    return true
  })
}
