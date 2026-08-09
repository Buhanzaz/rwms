import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react"
import type { ReactNode } from "react"
import { MemoryRouter } from "react-router-dom"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"

import type {
  AssistantConversation,
  AssistantConversationDetail,
  AssistantMessage,
  AssistantTurnEvent,
  CabinSearchNotice,
  CabinSearchResult,
} from "@/features/assistant/api/assistant-api"

const queryFixtures = vi.hoisted(() => ({
  conversations: [] as unknown[],
  detail: null as unknown,
  client: null as unknown,
}))

const authFixture = vi.hoisted(() => ({
  value: {
    accessToken: "access-token",
    currentUser: {
      id: "manager-1",
      username: "manager",
      displayName: "Менеджер",
      firstName: null,
      lastName: null,
      email: null,
      principalType: "USER" as const,
      globalRole: "RENTAL_MANAGER" as const,
      rentalAccess: true,
      warehouseAccessAll: false,
      warehouseAccesses: [],
    },
  },
}))

const queryRuntime = vi.hoisted(() => ({
  detailRefetch: vi.fn(),
  invalidateQueries: vi.fn(),
  listRefetch: vi.fn(),
  setQueryData: vi.fn(),
}))

const assistantApi = vi.hoisted(() => ({
  streamAssistantTurn: vi.fn(),
  updateAssistantSelection: vi.fn(),
}))

const filterSuggestions = {
  cabinTypes: ["БК-1"],
  finishes: ["ДВП"],
  dimensions: ["2x2"],
  categories: ["Обычная"],
  characteristics: [],
}

vi.mock("@tanstack/react-query", () => ({
  useQuery: ({ queryKey }: { queryKey: readonly unknown[] }) => {
    if (queryKey[0] === "assistant-conversations") {
      return queryKey.length === 1
        ? {
            data: queryFixtures.conversations,
            isPending: false,
            isError: false,
            refetch: queryRuntime.listRefetch,
          }
        : {
            data: queryFixtures.detail,
            isPending: false,
            isError: false,
            refetch: queryRuntime.detailRefetch,
          }
    }
    if (queryKey[0] === "rental-clients") {
      return {
        data: queryFixtures.client,
        isPending: false,
        isError: false,
        error: null,
      }
    }
    return { data: null, isPending: false, isError: false }
  },
  useMutation: () => ({ isPending: false, mutate: vi.fn() }),
  useQueryClient: () => ({
    invalidateQueries: queryRuntime.invalidateQueries,
    setQueryData: queryRuntime.setQueryData,
  }),
}))

vi.mock("@/features/assistant/api/assistant-api", async (importOriginal) => ({
  ...(await importOriginal<
    typeof import("@/features/assistant/api/assistant-api")
  >()),
  ...assistantApi,
}))

vi.mock("@/features/auth/use-auth", () => ({
  useAuth: () => authFixture.value,
}))

vi.mock("@/features/orders/components/order-client-chooser", () => ({
  OrderClientChooser: ({
    initialClient,
    responsibleManagerDisplayName,
  }: {
    initialClient?: { displayName: string } | null
    responsibleManagerDisplayName: string
  }) => (
    <div>
      {initialClient
        ? `Предвыбран клиент: ${initialClient.displayName}`
        : "Выбор клиента"}
      <output>{`Ответственный менеджер формы: ${responsibleManagerDisplayName}`}</output>
    </div>
  ),
}))

vi.mock("@/features/assistant/components/manager-booking-alert-dialog", () => ({
  ManagerBookingAlertDialog: () => null,
}))

vi.mock("@/features/assistant/components/assistant-search-results", () => ({
  AssistantSearchResults: ({
    result,
    selectedIds,
    onSelectionChange,
    collapsed,
    onCollapsedChange,
  }: {
    result: {
      groups: readonly {
        cabins: readonly { id: string }[]
      }[]
    }
    selectedIds: ReadonlySet<string>
    onSelectionChange: (next: Set<string>) => void
    collapsed?: boolean
    onCollapsedChange?: (collapsed: boolean) => void
  }) => (
    <div data-slot="assistant-search-results">
      <button
        type="button"
        aria-label={
          collapsed ? "Развернуть подбор бытовок" : "Скрыть подбор бытовок"
        }
        onClick={() => onCollapsedChange?.(!collapsed)}
      />
      {!collapsed ? (
        <>
          Найдено групп: {result.groups.length}
          <span data-slot="assistant-search-result-cabins">
            Найдено:{" "}
            {result.groups
              .flatMap((group) => group.cabins)
              .map((cabin) => cabin.id)
              .join(", ")}
          </span>
          <span data-slot="assistant-selected-ids">
            Выбрано: {[...selectedIds].join(", ")}
          </span>
          {result.groups
            .flatMap((group) => group.cabins)
            .map((cabin) => (
              <button
                key={cabin.id}
                type="button"
                onClick={() => onSelectionChange(new Set([cabin.id]))}
              >
                Выбрать {cabin.id}
              </button>
            ))}
        </>
      ) : null}
    </div>
  ),
}))

vi.mock("@/components/ui/message-scroller", () => ({
  MessageScrollerProvider: ({ children }: { children: ReactNode }) => (
    <>{children}</>
  ),
  MessageScroller: ({ children }: { children: ReactNode }) => (
    <div>{children}</div>
  ),
  MessageScrollerViewport: ({ children }: { children: ReactNode }) => (
    <div>{children}</div>
  ),
  MessageScrollerContent: ({ children }: { children: ReactNode }) => (
    <div>{children}</div>
  ),
  MessageScrollerItem: ({ children }: { children: ReactNode }) => (
    <div>{children}</div>
  ),
  MessageScrollerButton: () => null,
}))

import { AssistantPage } from "@/features/assistant/pages/assistant-page"

afterEach(cleanup)

const conversation: AssistantConversation = {
  id: "conversation-1",
  version: 1,
  clientId: "client-1",
  rentalInquiryId: "inquiry-1",
  clientType: "COMPANY",
  clientDisplayName: "ООО Север",
  archived: false,
  archivedAt: null,
  createdAt: "2026-07-29T10:00:00Z",
  updatedAt: "2026-07-29T10:00:00Z",
}

function activeSearchResult(count = 4): CabinSearchResult {
  return {
    warehouseId: "warehouse-1",
    expiresAt: new Date(Date.now() + 10 * 60_000).toISOString(),
    groups: [
      {
        group: {
          cabinType: "БК-1",
          finish: "ДВП",
          dimensions: null,
          category: "Обычная",
          quantity: 4,
        },
        cabins: Array.from({ length: count }, (_, index) => ({
          id: `cabin-${index + 1}`,
          version: 1,
          warehouseId: "warehouse-1",
          number: `БЫТ-${index + 1}`,
          status: "FREE" as const,
          rentalType: "БК-1",
          dimensions: "2x2",
          finishing: "ДВП",
          category: "Обычная",
          characteristics: null,
          linoleum: null,
          passport: {},
          tags: [],
          updatedAt: "2026-07-29T10:00:00Z",
        })),
      },
    ],
  }
}

function conversationDetail(
  lastSearchResult: CabinSearchResult | null,
  messages: AssistantMessage[] = []
): AssistantConversationDetail {
  return {
    conversation,
    messages,
    lastSearchResult: lastSearchResult
      ? {
          tool: "search_available_cabins",
          data: lastSearchResult,
          filterSuggestions,
        }
      : null,
    clarifications: [],
    currentSelection: lastSearchResult
      ? {
          inquiryId: conversation.rentalInquiryId,
          warehouseId: lastSearchResult.warehouseId,
          expiresAt: lastSearchResult.expiresAt,
          rentalItemIds: lastSearchResult.groups.flatMap((group) =>
            group.cabins.map((cabin) => cabin.id)
          ),
          items: lastSearchResult.groups.flatMap((group) => group.cabins),
        }
      : null,
  }
}

function searchResultEvent(
  result: CabinSearchResult,
  options: {
    resultMode?: "APPEND" | "REPLACE"
    notices?: CabinSearchNotice[]
  } = {}
): AssistantTurnEvent {
  return {
    event: "search.result",
    conversationId: conversation.id,
    result: {
      tool: "search_available_cabins",
      data: result,
      filterSuggestions,
      resultMode: options.resultMode,
      notices: options.notices,
    },
  }
}

const missingCabinsNotice: CabinSearchNotice = {
  code: "CABINS_NOT_FOUND",
  groups: [
    {
      cabinType: "БК-1",
      finish: "ДВП",
      dimensions: null,
      category: "ИТР",
      quantity: 3,
    },
  ],
  requestedQuantity: 3,
  foundQuantity: 0,
}

function composer() {
  const value = document.querySelector<HTMLElement>(
    '[data-slot="assistant-composer"]'
  )
  if (!value) throw new Error("Assistant composer was not rendered")
  return value
}

describe("AssistantPage composer", () => {
  beforeEach(() => {
    queryFixtures.conversations = [conversation]
    queryFixtures.detail = conversationDetail(activeSearchResult())
    queryFixtures.client = null
    queryRuntime.detailRefetch.mockReset()
    queryRuntime.detailRefetch.mockResolvedValue({ data: queryFixtures.detail })
    queryRuntime.invalidateQueries.mockReset()
    queryRuntime.invalidateQueries.mockResolvedValue(undefined)
    queryRuntime.listRefetch.mockReset()
    queryRuntime.listRefetch.mockImplementation(async () => ({
      data: queryFixtures.conversations,
    }))
    queryRuntime.setQueryData.mockReset()
    assistantApi.streamAssistantTurn.mockReset()
    assistantApi.streamAssistantTurn.mockImplementation(
      async ({ onEvent }: { onEvent: (event: { event: string }) => void }) => {
        onEvent({ event: "turn.completed" })
        queryFixtures.conversations = []
      }
    )
  })

  it("keeps the composer background-integrated with and without an active search result", () => {
    const { rerender } = render(
      <MemoryRouter>
        <AssistantPage />
      </MemoryRouter>
    )

    expect(screen.getByText("Найдено групп: 1")).toBeTruthy()
    expect(composer().className).not.toContain("border-t")

    queryFixtures.detail = conversationDetail(null)
    rerender(
      <MemoryRouter>
        <AssistantPage />
      </MemoryRouter>
    )

    expect(
      document.querySelector('[data-slot="assistant-search-results"]')
    ).toBeNull()
    expect(composer().className).not.toContain("border-t")
  })

  it("starts a new dialog with the exact client from the dossier query", () => {
    queryFixtures.client = {
      id: "99999999-9999-4999-8999-999999999999",
      displayName: "ООО Предвыбранный клиент",
    }

    render(
      <MemoryRouter
        initialEntries={[
          "/assistant?clientId=99999999-9999-4999-8999-999999999999",
        ]}
      >
        <AssistantPage />
      </MemoryRouter>
    )

    expect(
      screen.getByText("Предвыбран клиент: ООО Предвыбранный клиент")
    ).toBeTruthy()
    expect(
      screen.getByText("Ответственный менеджер формы: Менеджер")
    ).toBeTruthy()
  })

  it("keeps closed chats in history and lets the manager hide the cabin results", () => {
    const archivedConversation: AssistantConversation = {
      ...conversation,
      id: "conversation-archived",
      clientDisplayName: "ООО Архив",
      archived: true,
      archivedAt: "2026-07-29T10:05:00Z",
    }
    queryFixtures.conversations = [conversation, archivedConversation]

    render(
      <MemoryRouter>
        <AssistantPage />
      </MemoryRouter>
    )

    expect(screen.getByText("История")).toBeTruthy()
    expect(screen.getByText("ООО Архив")).toBeTruthy()
    expect(screen.getByText("Найдено групп: 1")).toBeTruthy()

    fireEvent.click(
      screen.getByRole("button", { name: "Скрыть подбор бытовок" })
    )
    expect(screen.queryByText("Найдено групп: 1")).toBeNull()

    fireEvent.click(
      screen.getByRole("button", { name: "Развернуть подбор бытовок" })
    )
    expect(screen.getByText("Найдено групп: 1")).toBeTruthy()
  })

  it("refreshes active conversations after a completed turn and leaves an archived selection", async () => {
    render(
      <MemoryRouter>
        <AssistantPage />
      </MemoryRouter>
    )

    fireEvent.click(screen.getByRole("button", { name: /^ООО Север/ }))
    fireEvent.change(
      screen.getByPlaceholderText("Напишите, какие бытовки подобрать…"),
      { target: { value: "Спасибо, подборка подтверждена" } }
    )
    fireEvent.click(screen.getByRole("button", { name: "Отправить сообщение" }))

    await waitFor(() =>
      expect(screen.getByText("Клиент перед началом диалога")).toBeTruthy()
    )
    expect(queryRuntime.invalidateQueries).toHaveBeenCalledWith({
      queryKey: ["assistant-conversations", "conversation-1"],
    })
    expect(queryRuntime.invalidateQueries).toHaveBeenCalledWith({
      queryKey: ["assistant-conversations"],
    })
    expect(queryRuntime.listRefetch).toHaveBeenCalledOnce()
  })

  it("shows a persisted structured missing-cabin notice after the assistant response", () => {
    const userMessage: AssistantMessage = {
      id: "user-with-notice",
      role: "USER",
      content: "Покажи три БК-1 ДВП ИТР",
      createdAt: "2026-07-29T10:01:00Z",
      searchNotices: [missingCabinsNotice],
    }
    queryFixtures.detail = conversationDetail(null, [
      userMessage,
      {
        id: "assistant-after-notice",
        role: "ASSISTANT",
        content: "Подбор обновлён.",
        createdAt: "2026-07-29T10:01:01Z",
      },
    ])

    render(
      <MemoryRouter>
        <AssistantPage />
      </MemoryRouter>
    )

    const notice = screen.getByText(
      "Не найдено: «БК-1 · ДВП · ИТР». Запрошено: 3; найдено: 0."
    )
    const assistant = screen.getByText("Подбор обновлён.")
    expect(
      assistant.compareDocumentPosition(notice) &
        Node.DOCUMENT_POSITION_FOLLOWING
    ).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
    expect(
      notice.closest('[data-slot="bubble"]')?.getAttribute("data-variant")
    ).toBe("destructive")
  })

  it("renders a live notice once after the streamed assistant response", async () => {
    queryFixtures.detail = conversationDetail(null)
    let finishTurn: (() => void) | undefined
    assistantApi.streamAssistantTurn.mockImplementation(
      async ({ onEvent }: { onEvent: (event: AssistantTurnEvent) => void }) => {
        const event = searchResultEvent(activeSearchResult(1), {
          notices: [missingCabinsNotice],
        })
        onEvent(event)
        onEvent(event)
        onEvent({
          event: "assistant.delta",
          conversationId: conversation.id,
          delta: "Подбор обновлён.",
        })
        await new Promise<void>((resolve) => {
          finishTurn = () => {
            onEvent({
              event: "turn.completed",
              conversationId: conversation.id,
            })
            resolve()
          }
        })
      }
    )

    render(
      <MemoryRouter>
        <AssistantPage />
      </MemoryRouter>
    )
    fireEvent.change(
      screen.getByPlaceholderText("Напишите, какие бытовки подобрать…"),
      { target: { value: "Покажи три БК-1 ДВП ИТР" } }
    )
    fireEvent.click(screen.getByRole("button", { name: "Отправить сообщение" }))

    await waitFor(() =>
      expect(
        screen.getAllByText(
          "Не найдено: «БК-1 · ДВП · ИТР». Запрошено: 3; найдено: 0."
        )
      ).toHaveLength(1)
    )
    const assistant = screen.getByText("Подбор обновлён.")
    const notice = screen.getByText(
      "Не найдено: «БК-1 · ДВП · ИТР». Запрошено: 3; найдено: 0."
    )
    expect(
      assistant.compareDocumentPosition(notice) &
        Node.DOCUMENT_POSITION_FOLLOWING
    ).toBe(Node.DOCUMENT_POSITION_FOLLOWING)
    expect(
      document.querySelector('[data-slot="bubble"][data-variant="destructive"]')
        ?.textContent
    ).toContain("Не найдено")

    await act(async () => finishTurn?.())
  })

  it("appends a new result and retains the cabins already selected by the manager", async () => {
    queryFixtures.detail = conversationDetail(activeSearchResult(1))
    let finishTurn: (() => void) | undefined
    assistantApi.streamAssistantTurn.mockImplementation(
      async ({ onEvent }: { onEvent: (event: AssistantTurnEvent) => void }) => {
        const appended = activeSearchResult(1)
        appended.groups[0].cabins[0] = {
          ...appended.groups[0].cabins[0],
          id: "cabin-2",
          number: "БЫТ-2",
        }
        onEvent(searchResultEvent(appended, { resultMode: "APPEND" }))
        await new Promise<void>((resolve) => {
          finishTurn = () => {
            onEvent({
              event: "turn.completed",
              conversationId: conversation.id,
            })
            resolve()
          }
        })
      }
    )

    render(
      <MemoryRouter>
        <AssistantPage />
      </MemoryRouter>
    )
    fireEvent.click(screen.getByRole("button", { name: "Выбрать cabin-1" }))
    expect(screen.getByText("Выбрано: cabin-1")).toBeTruthy()

    fireEvent.change(
      screen.getByPlaceholderText("Напишите, какие бытовки подобрать…"),
      { target: { value: "Добавь ещё одну БК-1" } }
    )
    fireEvent.click(screen.getByRole("button", { name: "Отправить сообщение" }))

    await waitFor(() => {
      expect(screen.getByText("Найдено: cabin-1, cabin-2")).toBeTruthy()
      expect(screen.getByText("Выбрано: cabin-1")).toBeTruthy()
    })

    await act(async () => finishTurn?.())
  })

  it("replaces the displayed result and reconciles selection after reload", async () => {
    queryFixtures.detail = conversationDetail(activeSearchResult(1))
    let finishTurn: (() => void) | undefined
    assistantApi.streamAssistantTurn.mockImplementation(
      async ({ onEvent }: { onEvent: (event: AssistantTurnEvent) => void }) => {
        const replacement = activeSearchResult(1)
        replacement.groups[0].cabins[0] = {
          ...replacement.groups[0].cabins[0],
          id: "cabin-2",
          number: "БЫТ-2",
        }
        onEvent(searchResultEvent(replacement, { resultMode: "REPLACE" }))
        await new Promise<void>((resolve) => {
          finishTurn = () => {
            queryFixtures.detail = conversationDetail(replacement)
            onEvent({
              event: "turn.completed",
              conversationId: conversation.id,
            })
            resolve()
          }
        })
      }
    )

    render(
      <MemoryRouter>
        <AssistantPage />
      </MemoryRouter>
    )
    fireEvent.click(screen.getByRole("button", { name: "Выбрать cabin-1" }))
    expect(screen.getByText("Выбрано: cabin-1")).toBeTruthy()

    fireEvent.change(
      screen.getByPlaceholderText("Напишите, какие бытовки подобрать…"),
      { target: { value: "Покажи другую бытовку" } }
    )
    fireEvent.click(screen.getByRole("button", { name: "Отправить сообщение" }))

    await waitFor(() => {
      expect(screen.getByText("Найдено: cabin-2")).toBeTruthy()
      expect(screen.getByText("Выбрано: cabin-1")).toBeTruthy()
    })

    await act(async () => finishTurn?.())
    await waitFor(() =>
      expect(screen.getByText("Выбрано: cabin-2")).toBeTruthy()
    )
  })

  it("applies streamed LLM removal and renewed hold expiry before terminal refetch", async () => {
    const initial = activeSearchResult(2)
    queryFixtures.detail = conversationDetail(initial)
    let finishTurn: (() => void) | undefined
    assistantApi.streamAssistantTurn.mockImplementation(
      async ({ onEvent }: { onEvent: (event: AssistantTurnEvent) => void }) => {
        const retained = initial.groups[0].cabins[1]
        const renewedExpiry = new Date(Date.now() + 20 * 60_000).toISOString()
        onEvent({
          event: "selection.updated",
          conversationId: conversation.id,
          toolCallId: "tool-remove-1",
          result: {
            tool: "remove_selected_cabins",
            data: {
              inquiryId: conversation.rentalInquiryId,
              warehouseId: initial.warehouseId,
              expiresAt: renewedExpiry,
              rentalItemIds: [retained.id],
              items: [retained],
              removedRentalItemIds: [initial.groups[0].cabins[0].id],
            },
          },
        })
        await new Promise<void>((resolve) => {
          finishTurn = () => {
            const retainedResult = {
              ...initial,
              expiresAt: renewedExpiry,
              groups: [
                {
                  ...initial.groups[0],
                  group: { ...initial.groups[0].group, quantity: 1 },
                  cabins: [retained],
                },
              ],
            }
            queryFixtures.detail = conversationDetail(retainedResult)
            onEvent({
              event: "turn.completed",
              conversationId: conversation.id,
            })
            resolve()
          }
        })
      }
    )

    render(
      <MemoryRouter>
        <AssistantPage />
      </MemoryRouter>
    )
    fireEvent.change(
      screen.getByPlaceholderText("Напишите, какие бытовки подобрать…"),
      { target: { value: "Удали БЫТ-1 из выборки" } }
    )
    fireEvent.click(screen.getByRole("button", { name: "Отправить сообщение" }))

    await waitFor(() => {
      expect(screen.getByText("Найдено: cabin-2")).toBeTruthy()
      expect(screen.getByText("Выбрано: cabin-2")).toBeTruthy()
      expect(screen.queryByText(/cabin-1, cabin-2/)).toBeNull()
    })
    expect(queryRuntime.setQueryData).toHaveBeenCalledWith(
      ["assistant-conversations", conversation.id],
      expect.any(Function)
    )

    await act(async () => finishTurn?.())
  })
})
