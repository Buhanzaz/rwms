import type { OrderClientChoice } from "@/features/orders/components/order-client-chooser"
import { apiErrorFromResponse, bearerRequest } from "@/lib/api-client"
import { getGatewayRuntimeConfig } from "@/lib/gateway-config"

export const ASSISTANT_QUERY_KEY = ["assistant-conversations"] as const

export type AssistantConversation = {
  id: string
  version: number
  clientId: string
  rentalInquiryId: string
  rentalOrderId: string | null
  clientType: string | null
  clientDisplayName: string | null
  archived: boolean
  archivedAt: string | null
  createdAt: string
  updatedAt: string
}

export type AssistantMessage = {
  id: string
  role: "USER" | "ASSISTANT"
  content: string
  createdAt: string
  /**
   * Structured availability warnings produced by the cabin-search tool for
   * this manager message. Keeping them on the user message makes the warning
   * durable when the conversation is fetched again.
   */
  searchNotices?: CabinSearchNotice[]
}

export type AssistantConversationDetail = {
  conversation: AssistantConversation
  messages: AssistantMessage[]
  lastSearchResult: AssistantTurnEvent["result"] | null
  clarifications: ClarificationQuestion[]
  currentSelection: CabinSelection | null
}

export type ClarificationKind =
  "CABIN_TYPE" | "FINISH" | "DIMENSIONS" | "CATEGORY" | "SEARCH_MERGE"

export type ClarificationStatus =
  "QUEUED" | "PENDING" | "ANSWERED" | "SUPERSEDED"

export type ClarificationOption = {
  id: string
  label: string
  value: string
}

export type ClarificationQuestion = {
  id: string
  branchKey: string
  sequenceNumber: number
  kind: ClarificationKind
  prompt: string
  status: ClarificationStatus
  options: ClarificationOption[]
  answeredOptionId: string | null
  createdAt: string
  answeredAt: string | null
}

export type AvailableCabin = {
  id: string
  version: number
  warehouseId: string
  number: string | null
  /** Assistant search returns only free cabins. */
  status: "FREE"
  rentalType: string | null
  dimensions: string | null
  finishing: string | null
  category: string | null
  characteristics: string | null
  linoleum: boolean | null
  passport: Record<string, unknown> | null
  tags: string[] | null
  updatedAt: string
}

export type CabinSearchGroup = {
  cabinType: string | null
  finish: string | null
  dimensions: string | null
  /** Independent cabin category, for example ИТР, Обычная or Новая. */
  category?: string | null
  /** Exact OR categories requested by the manager for one logical group. */
  categories?: string[] | null
  /** Free-text cabin characteristic requested by the manager. */
  characteristics?: string | null
  /** Exact linoleum requirement; null means that either value is acceptable. */
  linoleum?: boolean | null
  quantity: number
}

export type CabinSearchGroupResult = {
  group: CabinSearchGroup
  cabins: AvailableCabin[]
}

export type CabinSearchResult = {
  warehouseId: string
  expiresAt: string
  groups: CabinSearchGroupResult[]
}

export type CabinSearchResultMode = "APPEND" | "REPLACE"

export type CabinSearchNotice = {
  code: "CABINS_NOT_FOUND" | "CABINS_PARTIALLY_FOUND"
  groups: CabinSearchGroup[]
  requestedQuantity: number
  foundQuantity: number
}

export type CabinSearchResultEnvelope = {
  tool: "search_available_cabins"
  data: CabinSearchResult
  resultMode: CabinSearchResultMode
  notices: CabinSearchNotice[]
  filterSuggestions: CabinFilterSuggestions
}

export type CabinFilterSuggestions = {
  cabinTypes: string[]
  finishes: string[]
  dimensions: string[]
  categories: string[]
  characteristics: string[]
}

export type CabinSelection = {
  inquiryId: string
  warehouseId: string | null
  expiresAt: string | null
  rentalItemIds: string[]
  items: AvailableCabin[]
}

export type CabinSelectionUpdate = CabinSelection & {
  removedRentalItemIds: string[]
}

export type AssistantToolResult =
  | {
      tool:
        | "list_available_cabin_facets"
        | "search_available_cabins"
        | "request_cabin_clarifications"
        | "lookup_cabin_catalog"
        | "remove_selected_cabins"
      data?: unknown
      resultMode?: unknown
      notices?: unknown
      filterSuggestions?: unknown
    }
  | {
      code: string
      tool?: never
      data?: never
      resultMode?: never
      notices?: never
      filterSuggestions?: never
    }

export type AssistantTurnEvent = {
  event:
    | "turn.started"
    | "assistant.delta"
    | "tool.started"
    | "search.result"
    | "tool.completed"
    | "turn.completed"
    | "turn.failed"
    | "clarification.requested"
    | "clarification.answered"
    | "selection.updated"
  conversationId: string
  messageId?: string | null
  toolCallId?: string | null
  delta?: string | null
  result?: AssistantToolResult | null
  code?: string | null
  clarification?: ClarificationQuestion
}

export type ClarificationAnswer = {
  questionId: string
  optionId: string
}

export type AssistantTurnRequest =
  | { message: string; clarificationAnswer?: never }
  | { message?: never; clarificationAnswer: ClarificationAnswer }

function conversationsEndpoint(path = "") {
  return `${getGatewayRuntimeConfig().assistantApiBaseUrl}/v1/conversations${path}`
}

export function listAssistantConversations(
  accessToken: string,
  rentalOrderId?: string
) {
  const endpoint = new URL(conversationsEndpoint())
  if (rentalOrderId) endpoint.searchParams.set("rentalOrderId", rentalOrderId)
  return bearerRequest<AssistantConversation[]>(accessToken, endpoint)
}

export async function getAssistantConversation(
  accessToken: string,
  conversationId: string
) {
  return parseAssistantConversationDetail(
    await bearerRequest<unknown>(
      accessToken,
      conversationsEndpoint(`/${encodeURIComponent(conversationId)}`)
    )
  )
}

export function archiveAssistantConversation(
  accessToken: string,
  conversationId: string
) {
  return bearerRequest<void>(
    accessToken,
    conversationsEndpoint(`/${encodeURIComponent(conversationId)}`),
    { method: "DELETE" }
  )
}

export async function createAssistantConversation(params: {
  accessToken: string
  conversationId: string
  choice: OrderClientChoice
  rentalOrderId?: string
}) {
  const client =
    params.choice.kind === "existing"
      ? { clientId: params.choice.client.id }
      : {
          newClient: {
            clientType: params.choice.clientType,
            displayName: params.choice.displayName,
            phone: params.choice.phone,
            contactPerson: params.choice.contactPerson,
            email: params.choice.email,
            comment: params.choice.comment,
            source: params.choice.source,
          },
        }
  return bearerRequest<{
    conversation: AssistantConversation
    inquiry: { id: string; status: string | null }
    client: {
      id: string
      clientType: string | null
      displayName: string | null
    }
  }>(params.accessToken, conversationsEndpoint(), {
    method: "POST",
    body: JSON.stringify({
      conversationId: params.conversationId,
      ...client,
      rentalOrderId: params.rentalOrderId,
    }),
  })
}

function parseSseBlock(block: string): AssistantTurnEvent | null {
  const data = block
    .split(/\r?\n/)
    .filter((line) => line.startsWith("data:"))
    .map((line) => line.slice(5).trimStart())
    .join("\n")
  if (!data) return null
  const parsed = JSON.parse(data) as AssistantTurnEvent
  return parsed && typeof parsed.event === "string" ? parsed : null
}

export async function streamAssistantTurn(
  params: {
    accessToken: string
    conversationId: string
    onEvent: (event: AssistantTurnEvent) => void
    signal?: AbortSignal
  } & AssistantTurnRequest
) {
  const response = await fetch(
    conversationsEndpoint(
      `/${encodeURIComponent(params.conversationId)}/turns`
    ),
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${params.accessToken}`,
        Accept: "text/event-stream",
        "Content-Type": "application/json",
      },
      body: JSON.stringify(
        params.message !== undefined
          ? { message: params.message }
          : { clarificationAnswer: params.clarificationAnswer }
      ),
      signal: params.signal,
    }
  )
  if (!response.ok) {
    throw await apiErrorFromResponse(response)
  }
  if (!response.body) {
    throw new Error("Сервис чата не открыл поток ответа.")
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let pending = ""
  while (true) {
    const { done, value } = await reader.read()
    pending += decoder.decode(value, { stream: !done })
    const blocks = pending.split(/\r?\n\r?\n/)
    pending = blocks.pop() ?? ""
    for (const block of blocks) {
      const event = parseSseBlock(block)
      if (event) params.onEvent(event)
    }
    if (done) break
  }
  const finalEvent = parseSseBlock(pending)
  if (finalEvent) params.onEvent(finalEvent)
}

export function asCabinSearchResult(
  event: AssistantTurnEvent
): CabinSearchResult | null {
  return asCabinSearchResultEnvelope(event)?.data ?? null
}

export function asCabinSelectionUpdate(
  event: AssistantTurnEvent
): CabinSelectionUpdate | null {
  if (
    event.event !== "selection.updated" ||
    !isRecord(event.result) ||
    event.result.tool !== "remove_selected_cabins" ||
    !isRecord(event.result.data)
  ) {
    return null
  }
  const removedRentalItemIds = event.result.data.removedRentalItemIds
  if (
    !isStringArray(removedRentalItemIds) ||
    removedRentalItemIds.length === 0 ||
    removedRentalItemIds.length > 100 ||
    new Set(removedRentalItemIds).size !== removedRentalItemIds.length
  ) {
    return null
  }
  try {
    const selection = parseCabinSelection(event.result.data)
    if (
      removedRentalItemIds.some((id) => selection.rentalItemIds.includes(id))
    ) {
      return null
    }
    return {
      ...selection,
      removedRentalItemIds,
    }
  } catch {
    return null
  }
}

export function asCabinSearchResultEnvelope(
  event: AssistantTurnEvent
): CabinSearchResultEnvelope | null {
  if (event.event !== "search.result") return null
  return asPersistedCabinSearchResultEnvelope(event.result)
}

export function asPersistedCabinSearchResult(
  result: unknown
): CabinSearchResult | null {
  return asPersistedCabinSearchResultEnvelope(result)?.data ?? null
}

export function asPersistedCabinSearchResultEnvelope(
  result: unknown
): CabinSearchResultEnvelope | null {
  if (!isRecord(result) || result.tool !== "search_available_cabins") {
    return null
  }
  const data = parseCabinSearchResult(result.data)
  const filterSuggestions = parseCabinFilterSuggestions(
    result.filterSuggestions
  )
  if (!data || !filterSuggestions) return null
  return {
    tool: "search_available_cabins",
    data,
    resultMode: parseCabinSearchResultMode(result.resultMode),
    notices: parseCabinSearchNotices(result.notices),
    filterSuggestions,
  }
}

export async function updateAssistantSelection(params: {
  accessToken: string
  conversationId: string
  idempotencyKey: string
  warehouseId: string
  rentalItemIds: string[]
}) {
  return parseCabinSelection(
    await bearerRequest<unknown>(
      params.accessToken,
      conversationsEndpoint(
        `/${encodeURIComponent(params.conversationId)}/selection`
      ),
      {
        method: "PUT",
        headers: { "Idempotency-Key": params.idempotencyKey },
        body: JSON.stringify({
          warehouseId: params.warehouseId,
          rentalItemIds: [...new Set(params.rentalItemIds)],
        }),
      }
    )
  )
}

function parseCabinFilterSuggestions(
  value: unknown
): CabinFilterSuggestions | null {
  if (!isRecord(value)) return null
  const { cabinTypes, finishes, dimensions, categories, characteristics } =
    value
  if (
    !isStringArray(cabinTypes) ||
    !isStringArray(finishes) ||
    !isStringArray(dimensions) ||
    !isStringArray(categories) ||
    !isStringArray(characteristics)
  ) {
    return null
  }
  return { cabinTypes, finishes, dimensions, categories, characteristics }
}

function parseAssistantConversationDetail(
  value: unknown
): AssistantConversationDetail {
  if (
    !isRecord(value) ||
    !isRecord(value.conversation) ||
    !Array.isArray(value.messages) ||
    !Array.isArray(value.clarifications)
  ) {
    throw new Error("Сервис чата вернул некорректный диалог.")
  }

  const conversation = parseAssistantConversation(value.conversation)
  const messages = value.messages.map(parseAssistantMessage)
  const clarifications = value.clarifications.map(parseClarificationQuestion)
  const lastSearchResult =
    value.lastSearchResult === null
      ? null
      : asPersistedCabinSearchResultEnvelope(value.lastSearchResult)
  const currentSelection =
    value.currentSelection === null
      ? null
      : parseCabinSelection(value.currentSelection)

  if (
    !conversation ||
    messages.some((message) => message === null) ||
    clarifications.some((clarification) => clarification === null) ||
    (value.lastSearchResult !== null && !lastSearchResult) ||
    (currentSelection !== null && currentSelection.rentalItemIds.length === 0)
  ) {
    throw new Error("Сервис чата вернул некорректный диалог.")
  }

  return {
    conversation,
    messages: messages as AssistantMessage[],
    lastSearchResult,
    clarifications: clarifications as ClarificationQuestion[],
    currentSelection,
  }
}

function parseAssistantConversation(
  value: Record<string, unknown>
): AssistantConversation | null {
  const {
    id,
    version,
    clientId,
    rentalInquiryId,
    rentalOrderId,
    clientType,
    clientDisplayName,
    archived,
    archivedAt,
    createdAt,
    updatedAt,
  } = value
  if (
    typeof id !== "string" ||
    !isNonNegativeInteger(version) ||
    typeof clientId !== "string" ||
    typeof rentalInquiryId !== "string" ||
    !isNullableString(rentalOrderId) ||
    !isNullableString(clientType) ||
    !isNullableString(clientDisplayName) ||
    typeof archived !== "boolean" ||
    !isNullableDateTime(archivedAt) ||
    !isDateTime(createdAt) ||
    !isDateTime(updatedAt)
  ) {
    return null
  }
  return {
    id,
    version,
    clientId,
    rentalInquiryId,
    rentalOrderId,
    clientType,
    clientDisplayName,
    archived,
    archivedAt,
    createdAt,
    updatedAt,
  }
}

function parseAssistantMessage(value: unknown): AssistantMessage | null {
  if (!isRecord(value)) return null
  const { id, role, content, createdAt, searchNotices } = value
  if (
    typeof id !== "string" ||
    (role !== "USER" && role !== "ASSISTANT") ||
    typeof content !== "string" ||
    !isDateTime(createdAt)
  ) {
    return null
  }
  if (searchNotices !== undefined && !Array.isArray(searchNotices)) return null
  const parsedNotices =
    searchNotices === undefined
      ? undefined
      : parseCabinSearchNotices(searchNotices)
  if (
    searchNotices !== undefined &&
    parsedNotices?.length !== searchNotices.length
  ) {
    return null
  }
  return {
    id,
    role,
    content,
    createdAt,
    ...(parsedNotices === undefined ? {} : { searchNotices: parsedNotices }),
  }
}

function parseClarificationQuestion(
  value: unknown
): ClarificationQuestion | null {
  if (!isRecord(value) || !Array.isArray(value.options)) return null
  const {
    id,
    branchKey,
    sequenceNumber,
    kind,
    prompt,
    status,
    answeredOptionId,
    createdAt,
    answeredAt,
  } = value
  const options = value.options.map(parseClarificationOption)
  if (
    typeof id !== "string" ||
    typeof branchKey !== "string" ||
    !isPositiveInteger(sequenceNumber) ||
    !isClarificationKind(kind) ||
    typeof prompt !== "string" ||
    !isClarificationStatus(status) ||
    !isNullableString(answeredOptionId) ||
    !isDateTime(createdAt) ||
    !isNullableDateTime(answeredAt) ||
    options.length < 2 ||
    options.length > 30 ||
    options.some((option) => option === null) ||
    new Set(options.map((option) => option?.id)).size !== options.length ||
    (answeredOptionId !== null &&
      !options.some((option) => option?.id === answeredOptionId))
  ) {
    return null
  }
  return {
    id,
    branchKey,
    sequenceNumber,
    kind,
    prompt,
    status,
    options: options as ClarificationOption[],
    answeredOptionId,
    createdAt,
    answeredAt,
  }
}

function parseClarificationOption(value: unknown): ClarificationOption | null {
  if (
    !isRecord(value) ||
    typeof value.id !== "string" ||
    typeof value.label !== "string" ||
    typeof value.value !== "string"
  ) {
    return null
  }
  return { id: value.id, label: value.label, value: value.value }
}

function parseCabinSelection(value: unknown): CabinSelection {
  if (
    !isRecord(value) ||
    !Array.isArray(value.rentalItemIds) ||
    !Array.isArray(value.items)
  ) {
    throw new Error("Сервис чата вернул некорректную текущую выборку.")
  }
  const rentalItemIds = value.rentalItemIds
  if (!isStringArray(rentalItemIds)) {
    throw new Error("Сервис чата вернул некорректную текущую выборку.")
  }
  const items = value.items
    .map(parseAvailableCabin)
    .filter((item): item is AvailableCabin => item !== null)
  if (
    typeof value.inquiryId !== "string" ||
    value.inquiryId.length === 0 ||
    (value.warehouseId !== null && typeof value.warehouseId !== "string") ||
    (value.expiresAt !== null &&
      (typeof value.expiresAt !== "string" ||
        !Number.isFinite(Date.parse(value.expiresAt)))) ||
    new Set(rentalItemIds).size !== rentalItemIds.length ||
    rentalItemIds.length > 100 ||
    items.length !== value.items.length ||
    new Set(items.map((item) => item.id)).size !== items.length ||
    items.some((item) => !rentalItemIds.includes(item.id)) ||
    rentalItemIds.some((id) => !items.some((item) => item.id === id)) ||
    (rentalItemIds.length > 0 &&
      (value.warehouseId === null || value.expiresAt === null)) ||
    (value.warehouseId !== null &&
      items.some((item) => item.warehouseId !== value.warehouseId)) ||
    (rentalItemIds.length === 0 && value.expiresAt !== null)
  ) {
    throw new Error("Сервис чата вернул некорректную текущую выборку.")
  }
  return {
    inquiryId: value.inquiryId,
    warehouseId: value.warehouseId,
    expiresAt: value.expiresAt,
    rentalItemIds,
    items,
  }
}

export function parseCabinSearchNotices(value: unknown): CabinSearchNotice[] {
  if (!Array.isArray(value)) return []
  const notices = value
    .map(parseCabinSearchNotice)
    .filter((notice): notice is CabinSearchNotice => notice !== null)
  return mergeCabinSearchNotices([], notices)
}

export function mergeCabinSearchNotices(
  current: readonly CabinSearchNotice[] | undefined,
  next: readonly CabinSearchNotice[] | undefined
) {
  const noticesByKey = new Map<string, CabinSearchNotice>()
  for (const notice of [...(current ?? []), ...(next ?? [])]) {
    noticesByKey.set(cabinSearchNoticeKey(notice), notice)
  }
  return [...noticesByKey.values()]
}

export function cabinSearchNoticeKey(notice: CabinSearchNotice) {
  return JSON.stringify({
    code: notice.code,
    groups: notice.groups.map(cabinSearchGroupKey),
    requestedQuantity: notice.requestedQuantity,
    foundQuantity: notice.foundQuantity,
  })
}

export function cabinSearchGroupKey(group: CabinSearchGroup) {
  return JSON.stringify({
    cabinType: group.cabinType,
    finish: group.finish,
    dimensions: group.dimensions,
    category: group.category ?? null,
    categories: group.categories
      ? [...group.categories].sort((left, right) => left.localeCompare(right))
      : null,
    characteristics: group.characteristics ?? null,
    linoleum: group.linoleum ?? null,
  })
}

function parseCabinSearchResultMode(value: unknown): CabinSearchResultMode {
  return value === "APPEND" ? "APPEND" : "REPLACE"
}

function parseCabinSearchNotice(value: unknown): CabinSearchNotice | null {
  if (!isRecord(value)) return null
  const { code, groups, requestedQuantity, foundQuantity } = value
  if (
    (code !== "CABINS_NOT_FOUND" && code !== "CABINS_PARTIALLY_FOUND") ||
    !Array.isArray(groups) ||
    !isNonNegativeInteger(requestedQuantity) ||
    !isNonNegativeInteger(foundQuantity) ||
    foundQuantity > requestedQuantity
  ) {
    return null
  }
  const parsedGroups = groups
    .map(parseCabinSearchGroup)
    .filter((group): group is CabinSearchGroup => group !== null)
  if (parsedGroups.length !== groups.length || parsedGroups.length === 0) {
    return null
  }
  if (
    (code === "CABINS_NOT_FOUND" && foundQuantity !== 0) ||
    (code === "CABINS_PARTIALLY_FOUND" && foundQuantity === 0)
  ) {
    return null
  }
  return { code, groups: parsedGroups, requestedQuantity, foundQuantity }
}

function parseCabinSearchResult(value: unknown): CabinSearchResult | null {
  if (!isRecord(value)) return null
  const { warehouseId, expiresAt, groups } = value
  if (
    typeof warehouseId !== "string" ||
    typeof expiresAt !== "string" ||
    !Number.isFinite(Date.parse(expiresAt)) ||
    !Array.isArray(groups)
  ) {
    return null
  }
  const parsedGroups = groups
    .map(parseCabinSearchGroupResult)
    .filter((group): group is CabinSearchGroupResult => group !== null)
  return parsedGroups.length === groups.length
    ? { warehouseId, expiresAt, groups: parsedGroups }
    : null
}

function parseCabinSearchGroupResult(
  value: unknown
): CabinSearchGroupResult | null {
  if (!isRecord(value) || !Array.isArray(value.cabins)) return null
  const group = parseCabinSearchGroup(value.group)
  const cabins = value.cabins
    .map(parseAvailableCabin)
    .filter((cabin): cabin is AvailableCabin => cabin !== null)
  if (!group || cabins.length !== value.cabins.length) return null
  return { group, cabins }
}

function parseCabinSearchGroup(value: unknown): CabinSearchGroup | null {
  if (!isRecord(value)) return null
  const {
    cabinType,
    finish,
    dimensions,
    category,
    categories,
    characteristics,
    linoleum,
    quantity,
  } = value
  if (
    !isOptionalNullableString(cabinType) ||
    !isOptionalNullableString(finish) ||
    !isOptionalNullableString(dimensions) ||
    !isOptionalNullableString(category) ||
    !isOptionalNullableStringArray(categories) ||
    !isOptionalNullableString(characteristics) ||
    !isOptionalNullableBoolean(linoleum) ||
    !isPositiveInteger(quantity)
  ) {
    return null
  }
  return {
    cabinType: cabinType ?? null,
    finish: finish ?? null,
    dimensions: dimensions ?? null,
    ...(category === undefined ? {} : { category }),
    ...(categories === undefined ? {} : { categories }),
    ...(characteristics === undefined ? {} : { characteristics }),
    ...(linoleum === undefined ? {} : { linoleum }),
    quantity,
  }
}

function parseAvailableCabin(value: unknown): AvailableCabin | null {
  if (!isRecord(value)) return null
  const {
    id,
    version,
    warehouseId,
    number,
    status,
    rentalType,
    dimensions,
    finishing,
    category,
    characteristics,
    linoleum,
    passport,
    tags,
    updatedAt,
  } = value
  if (
    typeof id !== "string" ||
    !isNonNegativeInteger(version) ||
    typeof warehouseId !== "string" ||
    !isNullableString(number) ||
    status !== "FREE" ||
    !isOptionalNullableString(rentalType) ||
    !isOptionalNullableString(dimensions) ||
    !isOptionalNullableString(finishing) ||
    !isOptionalNullableString(category) ||
    !isOptionalNullableString(characteristics) ||
    !isOptionalNullableBoolean(linoleum) ||
    !isOptionalNullableRecord(passport) ||
    !isOptionalNullableStringArray(tags) ||
    typeof updatedAt !== "string" ||
    !Number.isFinite(Date.parse(updatedAt))
  ) {
    return null
  }
  return {
    id,
    version,
    warehouseId,
    number,
    status,
    rentalType: rentalType ?? null,
    dimensions: dimensions ?? null,
    finishing: finishing ?? null,
    category: category ?? null,
    characteristics: characteristics ?? null,
    linoleum: linoleum ?? null,
    passport: passport ?? null,
    tags: tags ?? null,
    updatedAt,
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value)
}

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === "string"
}

function isOptionalNullableString(
  value: unknown
): value is string | null | undefined {
  return value === undefined || isNullableString(value)
}

function isNullableBoolean(value: unknown): value is boolean | null {
  return value === null || typeof value === "boolean"
}

function isOptionalNullableBoolean(
  value: unknown
): value is boolean | null | undefined {
  return value === undefined || isNullableBoolean(value)
}

function isOptionalNullableRecord(
  value: unknown
): value is Record<string, unknown> | null | undefined {
  return value === undefined || value === null || isRecord(value)
}

function isNonNegativeInteger(value: unknown): value is number {
  return typeof value === "number" && Number.isInteger(value) && value >= 0
}

function isPositiveInteger(value: unknown): value is number {
  return isNonNegativeInteger(value) && value > 0
}

function isStringArray(value: unknown): value is string[] {
  return (
    Array.isArray(value) && value.every((entry) => typeof entry === "string")
  )
}

function isOptionalNullableStringArray(
  value: unknown
): value is string[] | null | undefined {
  return value === undefined || value === null || isStringArray(value)
}

function isDateTime(value: unknown): value is string {
  return typeof value === "string" && Number.isFinite(Date.parse(value))
}

function isNullableDateTime(value: unknown): value is string | null {
  return value === null || isDateTime(value)
}

function isClarificationKind(value: unknown): value is ClarificationKind {
  return (
    value === "CABIN_TYPE" ||
    value === "FINISH" ||
    value === "DIMENSIONS" ||
    value === "CATEGORY" ||
    value === "SEARCH_MERGE"
  )
}

function isClarificationStatus(value: unknown): value is ClarificationStatus {
  return (
    value === "QUEUED" ||
    value === "PENDING" ||
    value === "ANSWERED" ||
    value === "SUPERSEDED"
  )
}

export function isCabinSearchResultActive(
  result: CabinSearchResult,
  timestamp = Date.now()
) {
  const expiresAt = Date.parse(result.expiresAt)
  return Number.isFinite(expiresAt) && expiresAt > timestamp
}

export function mergeCabinSearchResults(
  current: CabinSearchResult | null,
  next: CabinSearchResult
): CabinSearchResult {
  if (!current || current.warehouseId !== next.warehouseId) return next

  const groupsByFilter = new Map<string, CabinSearchGroupResult>()
  for (const entry of [...current.groups, ...next.groups]) {
    const key = cabinSearchGroupKey(entry.group)
    const currentGroup = groupsByFilter.get(key)
    groupsByFilter.set(
      key,
      currentGroup
        ? {
            ...currentGroup,
            group: {
              ...currentGroup.group,
              quantity: currentGroup.group.quantity + entry.group.quantity,
            },
            cabins: [...currentGroup.cabins, ...entry.cabins],
          }
        : { ...entry, cabins: [...entry.cabins] }
    )
  }

  const includedCabinIds = new Set<string>()
  let groups = [...groupsByFilter.values()].map((entry) => ({
    ...entry,
    cabins: entry.cabins.filter((cabin) => {
      if (includedCabinIds.has(cabin.id)) return false
      includedCabinIds.add(cabin.id)
      return true
    }),
  }))
  if (groups.some((entry) => entry.cabins.length > 0)) {
    groups = groups.filter((entry) => entry.cabins.length > 0)
  }
  const currentExpiry = Date.parse(current.expiresAt)
  const nextExpiry = Date.parse(next.expiresAt)
  const expiresAt =
    Number.isFinite(currentExpiry) &&
    (!Number.isFinite(nextExpiry) || currentExpiry <= nextExpiry)
      ? current.expiresAt
      : next.expiresAt
  return { warehouseId: next.warehouseId, expiresAt, groups }
}
