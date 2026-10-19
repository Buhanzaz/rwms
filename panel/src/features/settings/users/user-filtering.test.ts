import { describe, expect, it } from "vitest"

import type { AdminUser } from "@/features/settings/users/model/users"

import {
  EMPTY_ADMIN_USER_FILTERS,
  buildAdminUserFilterOptions,
  filterAdminUsers,
  getAdminUserFilterName,
  hasActiveAdminUserFilters,
} from "./user-filtering"

const users: AdminUser[] = [
  {
    id: "00000000-0000-4000-8000-000000000001",
    version: 1,
    username: "warehouse.ivanov",
    firstName: "Иван",
    lastName: "Иванов",
    email: "ivanov@example.ru",
    timeZoneId: "Europe/Moscow",
    active: true,
    globalRole: "WAREHOUSE_MANAGER",
    mobileAppAccess: true,
    rentalAccess: false,
    warehouseAccesses: [],
  },
  {
    id: "00000000-0000-4000-8000-000000000002",
    version: 1,
    username: "rental.petrov",
    firstName: "Пётр",
    lastName: "Петров",
    email: "petrov@example.ru",
    timeZoneId: "Europe/Moscow",
    active: false,
    globalRole: "RENTAL_MANAGER",
    mobileAppAccess: false,
    rentalAccess: true,
    warehouseAccesses: [],
  },
]

describe("user filtering", () => {
  it("filters the loaded list by every dedicated filter field", () => {
    const result = filterAdminUsers(users, "", {
      logins: ["warehouse.ivanov"],
      names: ["Иванов Иван"],
      emails: ["ivanov@example.ru"],
      roles: ["WAREHOUSE_MANAGER"],
      statuses: ["ACTIVE"],
      mobileAppAccesses: ["ALLOWED"],
    })

    expect(result.map((user) => user.id)).toEqual([users[0]!.id])
  })

  it("uses exact multi-select values and labels names as surname then name", () => {
    const options = buildAdminUserFilterOptions([
      ...users,
      { ...users[1]!, id: "00000000-0000-4000-8000-000000000003", email: null },
    ])
    const result = filterAdminUsers(users, "", {
      ...EMPTY_ADMIN_USER_FILTERS,
      statuses: ["ACTIVE", "INACTIVE"],
      mobileAppAccesses: ["ALLOWED", "NOT_ALLOWED"],
    })

    expect(getAdminUserFilterName(users[0]!)).toBe("Иванов Иван")
    expect(options.names).toContainEqual({
      value: "Иванов Иван",
      label: "Иванов Иван",
    })
    expect(options.emails.map((option) => option.value)).not.toContain("")
    expect(result.map((user) => user.id)).toEqual(users.map((user) => user.id))
  })

  it("searches users by login", () => {
    const result = filterAdminUsers(
      users,
      "warehouse.ivanov",
      EMPTY_ADMIN_USER_FILTERS
    )

    expect(result.map((user) => user.id)).toEqual([users[0]!.id])
  })

  it("searches by surname and name in either order with е/ё and one typo", () => {
    const result = filterAdminUsers(
      users,
      "петров птер",
      EMPTY_ADMIN_USER_FILTERS
    )

    expect(result.map((user) => user.id)).toEqual([users[1]!.id])
  })

  it("searches users by email", () => {
    const result = filterAdminUsers(
      users,
      "petrov@example.ru",
      EMPTY_ADMIN_USER_FILTERS
    )

    expect(result.map((user) => user.id)).toEqual([users[1]!.id])
  })

  it("marks a non-default filter state as active", () => {
    expect(hasActiveAdminUserFilters(EMPTY_ADMIN_USER_FILTERS)).toBe(false)
    expect(
      hasActiveAdminUserFilters({
        ...EMPTY_ADMIN_USER_FILTERS,
        mobileAppAccesses: ["NOT_ALLOWED"],
      })
    ).toBe(true)
  })
})
