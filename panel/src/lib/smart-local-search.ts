function normalizeSearchText(value: unknown) {
  return String(value ?? "")
    .toLocaleLowerCase("ru-RU")
    .replaceAll("ё", "е")
    .normalize("NFKD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^\p{L}\p{N}]+/gu, " ")
    .trim()
    .replace(/\s+/g, " ")
}

function differsByAtMostOne(left: string, right: string) {
  if (Math.abs(left.length - right.length) > 1) return false

  let leftIndex = 0
  let rightIndex = 0
  let differences = 0

  while (leftIndex < left.length && rightIndex < right.length) {
    if (left[leftIndex] === right[rightIndex]) {
      leftIndex += 1
      rightIndex += 1
      continue
    }

    differences += 1
    if (differences > 1) return false

    if (
      left.length === right.length &&
      left[leftIndex] === right[rightIndex + 1] &&
      left[leftIndex + 1] === right[rightIndex]
    ) {
      leftIndex += 2
      rightIndex += 2
    } else if (left.length > right.length) leftIndex += 1
    else if (right.length > left.length) rightIndex += 1
    else {
      leftIndex += 1
      rightIndex += 1
    }
  }

  if (leftIndex < left.length || rightIndex < right.length) differences += 1
  return differences <= 1
}

function tokenScore(queryToken: string, candidateWords: string[]) {
  let best = 0

  candidateWords.forEach((candidate) => {
    if (candidate === queryToken) best = Math.max(best, 100)
    else if (candidate.startsWith(queryToken)) best = Math.max(best, 80)
    else if (candidate.includes(queryToken)) best = Math.max(best, 65)
    else if (
      queryToken.length >= 4 &&
      candidate.length >= 4 &&
      differsByAtMostOne(queryToken, candidate)
    ) {
      best = Math.max(best, 45)
    }
  })

  return best
}

export function smartLocalSearch<T>(
  values: T[],
  query: string,
  getSearchValues: (item: T) => unknown[]
) {
  const normalizedQuery = normalizeSearchText(query)
  if (!normalizedQuery) return values

  const queryTokens = Array.from(new Set(normalizedQuery.split(" ")))

  return values
    .map((item, index) => {
      const candidateWords = normalizeSearchText(
        getSearchValues(item).join(" ")
      )
        .split(" ")
        .filter(Boolean)
      const scores = queryTokens.map((token) =>
        tokenScore(token, candidateWords)
      )

      return {
        item,
        index,
        score: scores.every((score) => score > 0)
          ? scores.reduce((sum, score) => sum + score, 0)
          : -1,
      }
    })
    .filter((entry) => entry.score >= 0)
    .sort((left, right) => right.score - left.score || left.index - right.index)
    .map((entry) => entry.item)
}

export { normalizeSearchText }
