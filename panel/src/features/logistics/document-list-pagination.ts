import {
  bearerRequestWithResponse,
  invalidApiResponseError,
} from "@/lib/api-client"

const DOCUMENT_PAGE_SIZE = 100

function nonNegativeIntegerHeader(response: Response, name: string) {
  const raw = response.headers.get(name)
  if (raw === null || !/^\d+$/.test(raw)) {
    throw invalidApiResponseError(new Error(`${name} is missing or invalid`))
  }
  const value = Number(raw)
  if (!Number.isSafeInteger(value)) {
    throw invalidApiResponseError(new Error(`${name} is outside the safe range`))
  }
  return value
}

function booleanHeader(response: Response, name: string) {
  const raw = response.headers.get(name)
  if (raw === "true") return true
  if (raw === "false") return false
  throw invalidApiResponseError(new Error(`${name} is missing or invalid`))
}

/** Loads a complete exact-day or cabin-filtered document result without dropping later pages. */
export async function listAllLogisticsDocumentPages<T>(
  accessToken: string,
  endpoint: string,
  parsePage: (value: unknown) => T[]
) {
  const documents: T[] = []
  let requestedPage = 0

  while (true) {
    const url = new URL(endpoint, window.location.origin)
    url.searchParams.set("page", String(requestedPage))
    url.searchParams.set("size", String(DOCUMENT_PAGE_SIZE))
    const { data, response } = await bearerRequestWithResponse<unknown>(
      accessToken,
      url
    )
    const page = nonNegativeIntegerHeader(response, "X-RWMS-Page")
    const pageSize = nonNegativeIntegerHeader(response, "X-RWMS-Page-Size")
    const totalPages = nonNegativeIntegerHeader(
      response,
      "X-RWMS-Total-Pages"
    )
    const totalElements = nonNegativeIntegerHeader(
      response,
      "X-RWMS-Total-Elements"
    )
    const hasNext = booleanHeader(response, "X-RWMS-Has-Next")
    const expectedTotalPages =
      totalElements === 0 ? 0 : Math.ceil(totalElements / pageSize)

    if (
      page !== requestedPage ||
      pageSize !== DOCUMENT_PAGE_SIZE ||
      totalPages !== expectedTotalPages ||
      hasNext !== (page + 1 < totalPages)
    ) {
      throw invalidApiResponseError(
        new Error("Logistics document pagination metadata is inconsistent")
      )
    }

    documents.push(...parsePage(data))
    if (!hasNext) return documents
    requestedPage += 1
  }
}
