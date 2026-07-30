import { afterEach, describe, expect, it, vi } from "vitest"

import {
  asCabinSearchResultEnvelope,
  asCabinSearchResult,
  asPersistedCabinSearchResult,
  createAssistantConversation,
  isCabinSearchResultActive,
  mergeCabinSearchNotices,
  mergeCabinSearchResults,
  streamAssistantTurn,
  type CabinSearchResult,
  type AssistantTurnEvent,
} from "@/features/assistant/api/assistant-api"

const CONVERSATION_ID = "11111111-1111-4111-8111-111111111111"
const CLIENT_ID = "22222222-2222-4222-8222-222222222222"
const FIRST_EXPIRY = "2026-07-27T12:10:00Z"
const SECOND_EXPIRY = "2026-07-27T12:11:00Z"

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
          type: "LEGAL_ENTITY",
          displayName: "ООО Тест",
          phone: "+79990000000",
          email: null,
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

  it("parses incremental SSE events and exposes bounded search data", async () => {
    const search: AssistantTurnEvent = {
      event: "search.result",
      conversationId: CONVERSATION_ID,
      toolCallId: "44444444-4444-4444-8444-444444444444",
      result: {
        tool: "search_available_cabins",
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
    ])
    expect(asCabinSearchResult(events[1])).toEqual({
      warehouseId: "55555555-5555-4555-8555-555555555555",
      expiresAt: FIRST_EXPIRY,
      groups: [],
    })
  })

  it("parses a structured search notice and defaults an omitted mode to replace", () => {
    const result = {
      tool: "search_available_cabins",
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
