import { afterEach, describe, expect, it, vi } from "vitest"

import {
  asCabinSearchResultEnvelope,
  asCabinSearchResult,
  asCabinSelectionUpdate,
  asPersistedCabinSearchResult,
  createAssistantConversation,
  getAssistantConversation,
  isCabinSearchResultActive,
  listAssistantConversations,
  mergeCabinSearchNotices,
  mergeCabinSearchResults,
  streamAssistantTurn,
  updateAssistantSelection,
  type CabinSearchResult,
  type AssistantTurnEvent,
} from "@/features/assistant/api/assistant-api"
import { ApiError } from "@/lib/api-client"

const CONVERSATION_ID = "11111111-1111-4111-8111-111111111111"
const CLIENT_ID = "22222222-2222-4222-8222-222222222222"
const FIRST_EXPIRY = "2026-07-27T12:10:00Z"
const SECOND_EXPIRY = "2026-07-27T12:11:00Z"
const FILTER_SUGGESTIONS = {
  cabinTypes: ["БК-1"],
  finishes: ["ДВП"],
  dimensions: ["6x2.4"],
  categories: ["ИТР"],
  characteristics: ["С линолеумом"],
}
const WAREHOUSE_ID = "55555555-5555-4555-8555-555555555555"
const CABIN_ID = "99999999-9999-4999-8999-999999999999"
const ORDER_ID = "77777777-7777-4777-8777-777777777777"
const validCabin = {
  id: CABIN_ID,
  version: 1,
  warehouseId: WAREHOUSE_ID,
  number: "БЫТ-001",
  status: "FREE",
  rentalType: "БК-1",
  dimensions: "6x2.4",
  finishing: "ЛДСП",
  category: "Обычная",
  characteristics: "Линолеум",
  linoleum: true,
  passport: {},
  tags: [],
  updatedAt: "2026-07-27T12:00:00Z",
}

afterEach(() => {
  vi.unstubAllGlobals()
})

describe("assistant API", () => {
  it("creates a conversation only with the preselected client", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          conversation: {
            id: CONVERSATION_ID,
            version: 0,
            clientId: CLIENT_ID,
            rentalInquiryId: "33333333-3333-4333-8333-333333333333",
            rentalOrderId: null,
            clientType: "LEGAL_ENTITY",
            clientDisplayName: "ООО Тест",
            archived: false,
            archivedAt: null,
            createdAt: "2026-07-27T08:00:00Z",
            updatedAt: "2026-07-27T08:00:00Z",
          },
          inquiry: {
            id: "33333333-3333-4333-8333-333333333333",
            status: "ACTIVE",
          },
          client: {
            id: CLIENT_ID,
            clientType: "LEGAL_ENTITY",
            displayName: "ООО Тест",
          },
        }),
        { status: 201, headers: { "Content-Type": "application/json" } }
      )
    )
    vi.stubGlobal("fetch", fetchMock)

    await createAssistantConversation({
      accessToken: "access-token",
      conversationId: CONVERSATION_ID,
      choice: {
        kind: "existing",
        client: {
          id: CLIENT_ID,
          version: 1,
          type: "LEGAL_ENTITY",
          displayName: "ООО Тест",
          phone: "+79990000000",
          contactPerson: "Иван Иванов",
          additionalContacts: [],
          email: null,
          responsibleManagerId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
          responsibleManagerDisplayName: "Менеджер",
          comment: null,
          source: null,
          createdAt: "2026-07-27T08:00:00Z",
          updatedAt: "2026-07-27T08:00:00Z",
        },
      },
    })

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new URL(url).pathname).toBe("/api/assistant/v1/conversations")
    expect(new Headers(init.headers).get("Authorization")).toBe(
      "Bearer access-token"
    )
    expect(JSON.parse(String(init.body))).toEqual({
      conversationId: CONVERSATION_ID,
      clientId: CLIENT_ID,
    })
  })

  it("lists and creates a fresh assistant conversation for the current order", async () => {
    const conversation = {
      id: CONVERSATION_ID,
      version: 0,
      clientId: CLIENT_ID,
      rentalInquiryId: "33333333-3333-4333-8333-333333333333",
      rentalOrderId: ORDER_ID,
      clientType: "LEGAL_ENTITY",
      clientDisplayName: "ООО Тест",
      archived: false,
      archivedAt: null,
      createdAt: "2026-07-27T08:00:00Z",
      updatedAt: "2026-07-27T08:00:00Z",
    }
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(JSON.stringify([]), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        })
      )
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            conversation,
            inquiry: {
              id: conversation.rentalInquiryId,
              status: "ACTIVE",
            },
            client: {
              id: CLIENT_ID,
              clientType: "LEGAL_ENTITY",
              displayName: "ООО Тест",
            },
          }),
          { status: 201, headers: { "Content-Type": "application/json" } }
        )
      )
    vi.stubGlobal("fetch", fetchMock)

    await listAssistantConversations("access-token", ORDER_ID)
    const created = await createAssistantConversation({
      accessToken: "access-token",
      conversationId: CONVERSATION_ID,
      rentalOrderId: ORDER_ID,
      choice: {
        kind: "existing",
        client: {
          id: CLIENT_ID,
          version: 1,
          type: "LEGAL_ENTITY",
          displayName: "ООО Тест",
          phone: "+79990000000",
          contactPerson: "Иван Иванов",
          additionalContacts: [],
          email: null,
          responsibleManagerId: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
          responsibleManagerDisplayName: "Менеджер",
          comment: null,
          source: null,
          createdAt: "2026-07-27T08:00:00Z",
          updatedAt: "2026-07-27T08:00:00Z",
        },
      },
    })

    const [listUrl] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new URL(listUrl).searchParams.get("rentalOrderId")).toBe(ORDER_ID)

    const [, createInit] = fetchMock.mock.calls[1] as [string, RequestInit]
    expect(JSON.parse(String(createInit.body))).toEqual({
      conversationId: CONVERSATION_ID,
      clientId: CLIENT_ID,
      rentalOrderId: ORDER_ID,
    })
    expect(created.conversation.rentalOrderId).toBe(ORDER_ID)
  })

  it("parses incremental SSE events and exposes bounded search data", async () => {
    const search: AssistantTurnEvent = {
      event: "search.result",
      conversationId: CONVERSATION_ID,
      toolCallId: "44444444-4444-4444-8444-444444444444",
      result: {
        tool: "search_available_cabins",
        filterSuggestions: FILTER_SUGGESTIONS,
        data: {
          warehouseId: "55555555-5555-4555-8555-555555555555",
          expiresAt: FIRST_EXPIRY,
          groups: [],
        },
      },
    }
    const body = [
      `event: assistant.delta\ndata: ${JSON.stringify({
        event: "assistant.delta",
        conversationId: CONVERSATION_ID,
        delta: "Нашёл ",
      })}\n\n`,
      `event: search.result\ndata: ${JSON.stringify(search)}\n\n`,
      `event: selection.updated\ndata: ${JSON.stringify({
        event: "selection.updated",
        conversationId: CONVERSATION_ID,
        toolCallId: "77777777-7777-4777-8777-777777777777",
        result: {
          tool: "remove_selected_cabins",
          data: {
            inquiryId: "33333333-3333-4333-8333-333333333333",
            warehouseId: WAREHOUSE_ID,
            expiresAt: FIRST_EXPIRY,
            rentalItemIds: [CABIN_ID],
            items: [validCabin],
            removedRentalItemIds: ["aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"],
          },
        },
      })}\n\n`,
      `event: tool.completed\ndata: ${JSON.stringify({
        event: "tool.completed",
        conversationId: CONVERSATION_ID,
        toolCallId: "88888888-8888-4888-8888-888888888888",
        result: { tool: "request_cabin_clarifications", data: {} },
      })}\n\n`,
      `event: tool.completed\ndata: ${JSON.stringify({
        event: "tool.completed",
        conversationId: CONVERSATION_ID,
        toolCallId: "99999999-9999-4999-8999-999999999999",
        result: { tool: "lookup_cabin_catalog", data: {} },
      })}\n\n`,
    ]
    const stream = new ReadableStream({
      start(controller) {
        body.forEach((chunk) =>
          controller.enqueue(new TextEncoder().encode(chunk))
        )
        controller.close()
      },
    })
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(stream, {
          status: 200,
          headers: { "Content-Type": "text/event-stream" },
        })
      )
    )
    const events: AssistantTurnEvent[] = []

    await streamAssistantTurn({
      accessToken: "access-token",
      conversationId: CONVERSATION_ID,
      message: "Покажи БК-1",
      onEvent: (event) => events.push(event),
    })

    expect(events.map((event) => event.event)).toEqual([
      "assistant.delta",
      "search.result",
      "selection.updated",
      "tool.completed",
      "tool.completed",
    ])
    expect(
      events
        .slice(-2)
        .map((event) =>
          event.result && "tool" in event.result ? event.result.tool : null
        )
    ).toEqual(["request_cabin_clarifications", "lookup_cabin_catalog"])
    expect(asCabinSelectionUpdate(events[2])).toMatchObject({
      expiresAt: FIRST_EXPIRY,
      rentalItemIds: [CABIN_ID],
      removedRentalItemIds: ["aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"],
    })
    expect(asCabinSearchResult(events[1])).toEqual({
      warehouseId: "55555555-5555-4555-8555-555555555555",
      expiresAt: FIRST_EXPIRY,
      groups: [],
    })
  })

  it("sends a clarification answer as the exclusive turn body and keeps its SSE update", async () => {
    const clarification = {
      id: "66666666-6666-4666-8666-666666666666",
      branchKey: "finish:osb",
      sequenceNumber: 1,
      kind: "DIMENSIONS" as const,
      prompt: "Какой размер ОСБ нужен?",
      status: "ANSWERED" as const,
      options: [
        {
          id: "77777777-7777-4777-8777-777777777777",
          label: "6x2.4",
          value: "6x2.4",
        },
      ],
      answeredOptionId: "77777777-7777-4777-8777-777777777777",
      createdAt: "2026-07-27T12:00:00Z",
      answeredAt: "2026-07-27T12:01:00Z",
    }
    const stream = new ReadableStream({
      start(controller) {
        controller.enqueue(
          new TextEncoder().encode(
            `data: ${JSON.stringify({
              event: "clarification.answered",
              conversationId: CONVERSATION_ID,
              clarification,
            })}\n\n`
          )
        )
        controller.close()
      },
    })
    const fetchMock = vi.fn().mockResolvedValue(new Response(stream))
    vi.stubGlobal("fetch", fetchMock)
    const events: AssistantTurnEvent[] = []

    await streamAssistantTurn({
      accessToken: "access-token",
      conversationId: CONVERSATION_ID,
      clarificationAnswer: {
        questionId: clarification.id,
        optionId: clarification.options[0].id,
      },
      onEvent: (event) => events.push(event),
    })

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(JSON.parse(String(init.body))).toEqual({
      clarificationAnswer: {
        questionId: clarification.id,
        optionId: clarification.options[0].id,
      },
    })
    expect(events[0]).toMatchObject({
      event: "clarification.answered",
      clarification,
    })
  })

  it("puts the complete remaining selection and accepts an immediate release", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          inquiryId: "33333333-3333-4333-8333-333333333333",
          warehouseId: null,
          expiresAt: null,
          rentalItemIds: [],
          items: [],
        }),
        { headers: { "Content-Type": "application/json" } }
      )
    )
    vi.stubGlobal("fetch", fetchMock)

    await expect(
      updateAssistantSelection({
        accessToken: "access-token",
        conversationId: CONVERSATION_ID,
        idempotencyKey: "88888888-8888-4888-8888-888888888888",
        warehouseId: "55555555-5555-4555-8555-555555555555",
        rentalItemIds: [],
      })
    ).resolves.toMatchObject({ expiresAt: null, rentalItemIds: [] })

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(new URL(url).pathname).toBe(
      `/api/assistant/v1/conversations/${CONVERSATION_ID}/selection`
    )
    expect(init.method).toBe("PUT")
    expect(new Headers(init.headers).get("Idempotency-Key")).toBe(
      "88888888-8888-4888-8888-888888888888"
    )
    expect(JSON.parse(String(init.body))).toEqual({
      warehouseId: "55555555-5555-4555-8555-555555555555",
      rentalItemIds: [],
    })
  })

  it.each([
    {
      name: "mismatched item IDs",
      response: {
        inquiryId: "33333333-3333-4333-8333-333333333333",
        warehouseId: WAREHOUSE_ID,
        expiresAt: FIRST_EXPIRY,
        rentalItemIds: [CABIN_ID],
        items: [{ ...validCabin, id: "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa" }],
      },
    },
    {
      name: "duplicate item facts",
      response: {
        inquiryId: "33333333-3333-4333-8333-333333333333",
        warehouseId: WAREHOUSE_ID,
        expiresAt: FIRST_EXPIRY,
        rentalItemIds: [CABIN_ID],
        items: [validCabin, validCabin],
      },
    },
    {
      name: "nonempty selection without an expiry",
      response: {
        inquiryId: "33333333-3333-4333-8333-333333333333",
        warehouseId: WAREHOUSE_ID,
        expiresAt: null,
        rentalItemIds: [CABIN_ID],
        items: [validCabin],
      },
    },
    {
      name: "nonempty selection without a warehouse",
      response: {
        inquiryId: "33333333-3333-4333-8333-333333333333",
        warehouseId: null,
        expiresAt: FIRST_EXPIRY,
        rentalItemIds: [CABIN_ID],
        items: [validCabin],
      },
    },
    {
      name: "empty IDs with an item fact",
      response: {
        inquiryId: "33333333-3333-4333-8333-333333333333",
        warehouseId: WAREHOUSE_ID,
        expiresAt: null,
        rentalItemIds: [],
        items: [validCabin],
      },
    },
    {
      name: "released selection with a stale expiry",
      response: {
        inquiryId: "33333333-3333-4333-8333-333333333333",
        warehouseId: WAREHOUSE_ID,
        expiresAt: FIRST_EXPIRY,
        rentalItemIds: [],
        items: [],
      },
    },
  ])(
    "rejects a malformed authoritative selection: $name",
    async ({ response }) => {
      vi.stubGlobal(
        "fetch",
        vi.fn().mockResolvedValue(
          new Response(JSON.stringify(response), {
            headers: { "Content-Type": "application/json" },
          })
        )
      )

      await expect(
        updateAssistantSelection({
          accessToken: "access-token",
          conversationId: CONVERSATION_ID,
          idempotencyKey: "88888888-8888-4888-8888-888888888888",
          warehouseId: WAREHOUSE_ID,
          rentalItemIds: response.rentalItemIds,
        })
      ).rejects.toThrow("некорректную текущую выборку")
    }
  )

  it("parses the authoritative current selection when reloading a conversation", async () => {
    const currentSelection = {
      inquiryId: "33333333-3333-4333-8333-333333333333",
      warehouseId: WAREHOUSE_ID,
      expiresAt: FIRST_EXPIRY,
      rentalItemIds: [CABIN_ID],
      items: [validCabin],
    }
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            conversation: {
              id: CONVERSATION_ID,
              version: 1,
              clientId: CLIENT_ID,
              rentalInquiryId: currentSelection.inquiryId,
              rentalOrderId: null,
              clientType: "LEGAL_ENTITY",
              clientDisplayName: "ООО Тест",
              archived: false,
              archivedAt: null,
              createdAt: "2026-07-27T08:00:00Z",
              updatedAt: "2026-07-27T12:00:00Z",
            },
            messages: [],
            lastSearchResult: null,
            clarifications: [],
            currentSelection,
          }),
          { headers: { "Content-Type": "application/json" } }
        )
      )
    )

    await expect(
      getAssistantConversation("access-token", CONVERSATION_ID)
    ).resolves.toMatchObject({ currentSelection })
  })

  it("rejects an empty currentSelection object instead of trusting it as conversation state", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            conversation: {
              id: CONVERSATION_ID,
              version: 1,
              clientId: CLIENT_ID,
              rentalInquiryId: "33333333-3333-4333-8333-333333333333",
              rentalOrderId: null,
              clientType: "LEGAL_ENTITY",
              clientDisplayName: "ООО Тест",
              archived: false,
              archivedAt: null,
              createdAt: "2026-07-27T08:00:00Z",
              updatedAt: "2026-07-27T12:00:00Z",
            },
            messages: [],
            lastSearchResult: null,
            clarifications: [],
            currentSelection: {
              inquiryId: "33333333-3333-4333-8333-333333333333",
              warehouseId: null,
              expiresAt: null,
              rentalItemIds: [],
              items: [],
            },
          }),
          { headers: { "Content-Type": "application/json" } }
        )
      )
    )

    await expect(
      getAssistantConversation("access-token", CONVERSATION_ID)
    ).rejects.toThrow("некорректный диалог")
  })

  it("maps turn Problem Details through the shared status-bearing error", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            type: "about:blank",
            title: "Forbidden",
            status: 403,
            detail: "Для диалога больше нет доступа.",
            code: "ASSISTANT_FORBIDDEN",
          }),
          {
            status: 403,
            headers: { "Content-Type": "application/problem+json" },
          }
        )
      )
    )

    let failure: unknown
    try {
      await streamAssistantTurn({
        accessToken: "access-token",
        conversationId: CONVERSATION_ID,
        message: "Покажи БК-1",
        onEvent: vi.fn(),
      })
    } catch (error) {
      failure = error
    }

    expect(failure).toBeInstanceOf(ApiError)
    expect(failure).toMatchObject({
      status: 403,
      code: "ASSISTANT_FORBIDDEN",
      message: "Для диалога больше нет доступа.",
    })
  })

  it("keeps malformed turn Problem Details typed and safe", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response("<html>upstream error</html>", {
          status: 503,
          headers: { "Content-Type": "application/problem+json" },
        })
      )
    )

    let failure: unknown
    try {
      await streamAssistantTurn({
        accessToken: "access-token",
        conversationId: CONVERSATION_ID,
        message: "Покажи БК-1",
        onEvent: vi.fn(),
      })
    } catch (error) {
      failure = error
    }

    expect(failure).toBeInstanceOf(ApiError)
    expect(failure).toMatchObject({
      status: 503,
      code: null,
      message: "Запрос завершился с ошибкой 503",
    })
  })

  it("parses a structured search notice and defaults an omitted mode to replace", () => {
    const result = {
      tool: "search_available_cabins",
      filterSuggestions: FILTER_SUGGESTIONS,
      data: {
        warehouseId: "55555555-5555-4555-8555-555555555555",
        expiresAt: FIRST_EXPIRY,
        groups: [],
      },
      notices: [
        {
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
        },
      ],
    } as const

    const event: AssistantTurnEvent = {
      event: "search.result",
      conversationId: CONVERSATION_ID,
      result,
    }

    expect(asCabinSearchResultEnvelope(event)).toEqual({
      ...result,
      resultMode: "REPLACE",
    })
    expect(asPersistedCabinSearchResult(result)).toEqual(result.data)
    expect(
      asCabinSearchResultEnvelope({
        ...event,
        result: { ...result, resultMode: "APPEND" },
      })?.resultMode
    ).toBe("APPEND")
  })

  it("keeps only valid unique notices from repeated search envelopes", () => {
    const notice = {
      code: "CABINS_PARTIALLY_FOUND" as const,
      groups: [
        {
          cabinType: "БК-2",
          finish: null,
          dimensions: "2x6",
          category: "Обычная",
          quantity: 5,
        },
      ],
      requestedQuantity: 5,
      foundQuantity: 2,
    }

    expect(mergeCabinSearchNotices([notice], [notice])).toEqual([notice])
  })

  it("merges separate search tool calls into one useful carousel", () => {
    const exact = {
      warehouseId: "55555555-5555-4555-8555-555555555555",
      expiresAt: FIRST_EXPIRY,
      groups: [
        {
          group: {
            cabinType: "БК-1",
            finish: "ДВП",
            dimensions: null,
            category: "Обычная",
            quantity: 2,
          },
          cabins: [],
        },
      ],
    } as CabinSearchResult
    const byType = {
      warehouseId: exact.warehouseId,
      expiresAt: SECOND_EXPIRY,
      groups: [
        {
          group: {
            cabinType: "БК-1",
            finish: null,
            dimensions: null,
            category: "Обычная",
            quantity: 2,
          },
          cabins: [{ id: "cabin-1", number: "БЫТ-111" }],
        },
      ],
    } as CabinSearchResult
    const byFinish = {
      warehouseId: exact.warehouseId,
      expiresAt: SECOND_EXPIRY,
      groups: [
        {
          group: {
            cabinType: null,
            finish: "ДВП",
            dimensions: null,
            category: "Новая",
            quantity: 2,
          },
          cabins: [{ id: "cabin-2", number: "БЫТ-071" }],
        },
      ],
    } as CabinSearchResult

    const merged = mergeCabinSearchResults(
      mergeCabinSearchResults(exact, byType),
      byFinish
    )

    expect(merged.groups).toHaveLength(2)
    expect(merged.groups.map((entry) => entry.cabins[0]?.id)).toEqual([
      "cabin-1",
      "cabin-2",
    ])
    expect(merged.expiresAt).toBe(FIRST_EXPIRY)
    expect(
      isCabinSearchResultActive(merged, Date.parse("2026-07-27T12:09:59Z"))
    ).toBe(true)
    expect(isCabinSearchResultActive(merged, Date.parse(FIRST_EXPIRY))).toBe(
      false
    )
  })

  it("appends cabins into the same displayed filter without duplicating IDs", () => {
    const current = {
      warehouseId: "55555555-5555-4555-8555-555555555555",
      expiresAt: FIRST_EXPIRY,
      groups: [
        {
          group: {
            cabinType: "БК-1",
            finish: "ДВП",
            dimensions: null,
            category: "ИТР",
            quantity: 1,
          },
          cabins: [{ id: "cabin-1", number: "БЫТ-001" }],
        },
      ],
    } as CabinSearchResult
    const next = {
      warehouseId: current.warehouseId,
      expiresAt: SECOND_EXPIRY,
      groups: [
        {
          group: {
            ...current.groups[0].group,
            quantity: 2,
          },
          cabins: [
            { id: "cabin-1", number: "БЫТ-001" },
            { id: "cabin-2", number: "БЫТ-002" },
          ],
        },
      ],
    } as CabinSearchResult

    const merged = mergeCabinSearchResults(current, next)

    expect(merged.groups).toHaveLength(1)
    expect(merged.groups[0].group.quantity).toBe(3)
    expect(merged.groups[0].cabins.map((cabin) => cabin.id)).toEqual([
      "cabin-1",
      "cabin-2",
    ])
  })

  it("merges the same OR categories even when the model changes their order", () => {
    const current = {
      warehouseId: "55555555-5555-4555-8555-555555555555",
      expiresAt: FIRST_EXPIRY,
      groups: [
        {
          group: {
            cabinType: "БК-1",
            finish: "ДВП",
            dimensions: null,
            categories: ["Обычная", "ИТР"],
            quantity: 1,
          },
          cabins: [{ id: "cabin-1", number: "БЫТ-001" }],
        },
      ],
    } as CabinSearchResult
    const next = {
      warehouseId: current.warehouseId,
      expiresAt: SECOND_EXPIRY,
      groups: [
        {
          group: {
            ...current.groups[0].group,
            categories: ["ИТР", "Обычная"],
            quantity: 1,
          },
          cabins: [{ id: "cabin-2", number: "БЫТ-002" }],
        },
      ],
    } as CabinSearchResult

    const merged = mergeCabinSearchResults(current, next)

    expect(merged.groups).toHaveLength(1)
    expect(merged.groups[0].cabins.map((cabin) => cabin.id)).toEqual([
      "cabin-1",
      "cabin-2",
    ])
  })
})
