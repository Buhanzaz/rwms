import { afterEach, describe, expect, it, vi } from "vitest"

import {
  AdminUserResponseProtocolError,
  createAdminUser,
  listAdminUsers,
  replaceAdminUserWarehouseAccesses,
  updateAdminUser,
} from "@/features/settings/users/api/users-api"
import type {
  AdminUser,
  AdminUserProfileInput,
  CreateAdminUserInput,
} from "@/features/settings/users/model/users"

const USER_ID = "9e5e2f71-53d2-4ab1-a740-28c13b2b02e4"
const ACCESS_TOKEN = "admin-access-token"

const profile = {
  username: "warehouse.manager",
  firstName: "Иван",
  lastName: "Иванов",
  email: "manager@example.test",
  timeZoneId: "Europe/Moscow",
  active: true,
  globalRole: "WAREHOUSE_MANAGER",
  mobileAppAccess: true,
  rentalAccess: true,
} satisfies AdminUserProfileInput

const createInput = {
  ...profile,
  password: "LongEnoughPassword1!",
  warehouseAccesses: [],
} satisfies CreateAdminUserInput

const validUser = {
  id: USER_ID,
  version: 3,
  ...profile,
  warehouseAccesses: [],
} satisfies AdminUser

function jsonResponse(body: unknown) {
  return new Response(JSON.stringify(body), {
    status: 200,
    headers: { "content-type": "application/json" },
  })
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe("admin users API entitlement contract", () => {
  const requests = [
    {
      name: "list",
      call: () => listAdminUsers(ACCESS_TOKEN),
      validResponse: [validUser],
      invalidMobileAppAccessResponse: [
        { ...validUser, mobileAppAccess: undefined },
      ],
      invalidRentalAccessResponse: [{ ...validUser, rentalAccess: undefined }],
    },
    {
      name: "create",
      call: () => createAdminUser(ACCESS_TOKEN, createInput),
      validResponse: validUser,
      invalidMobileAppAccessResponse: {
        ...validUser,
        mobileAppAccess: undefined,
      },
      invalidRentalAccessResponse: { ...validUser, rentalAccess: undefined },
    },
    {
      name: "update",
      call: () => updateAdminUser(ACCESS_TOKEN, USER_ID, 2, profile),
      validResponse: validUser,
      invalidMobileAppAccessResponse: { ...validUser, mobileAppAccess: "true" },
      invalidRentalAccessResponse: { ...validUser, rentalAccess: "true" },
    },
    {
      name: "replace warehouse accesses",
      call: () =>
        replaceAdminUserWarehouseAccesses(ACCESS_TOKEN, USER_ID, 2, {
          accesses: [],
        }),
      validResponse: validUser,
      invalidMobileAppAccessResponse: { ...validUser, mobileAppAccess: null },
      invalidRentalAccessResponse: { ...validUser, rentalAccess: null },
    },
  ]

  it.each(requests)(
    "accepts valid entitlement booleans from $name",
    async ({ call, validResponse }) => {
      vi.spyOn(globalThis, "fetch").mockResolvedValue(
        jsonResponse(validResponse)
      )

      await expect(call()).resolves.toEqual(validResponse)
    }
  )

  it.each(requests)(
    "rejects a missing or invalid mobileAppAccess from $name",
    async ({ call, invalidMobileAppAccessResponse }) => {
      vi.spyOn(globalThis, "fetch").mockResolvedValue(
        jsonResponse(invalidMobileAppAccessResponse)
      )

      const request = call()

      await expect(request).rejects.toBeInstanceOf(
        AdminUserResponseProtocolError
      )
      await expect(request).rejects.toThrow(/mobileAppAccess/)
    }
  )

  it.each(requests)(
    "rejects a missing or invalid rentalAccess from $name",
    async ({ call, invalidRentalAccessResponse }) => {
      vi.spyOn(globalThis, "fetch").mockResolvedValue(
        jsonResponse(invalidRentalAccessResponse)
      )

      const request = call()

      await expect(request).rejects.toBeInstanceOf(
        AdminUserResponseProtocolError
      )
      await expect(request).rejects.toThrow(/rentalAccess/)
    }
  )

  it("serializes rentalAccess for create and update requests", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockImplementation(async () => jsonResponse(validUser))

    await createAdminUser(ACCESS_TOKEN, createInput)
    await updateAdminUser(ACCESS_TOKEN, USER_ID, 2, {
      ...profile,
      rentalAccess: false,
    })

    const createRequest = fetchMock.mock.calls[0]?.[1]
    const updateRequest = fetchMock.mock.calls[1]?.[1]

    expect(JSON.parse(String(createRequest?.body))).toMatchObject({
      rentalAccess: true,
    })
    expect(JSON.parse(String(updateRequest?.body))).toMatchObject({
      expectedVersion: 2,
      rentalAccess: false,
    })
  })
})
