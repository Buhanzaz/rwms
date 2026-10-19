import type { PropsWithChildren } from "react"
import { cleanup, render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, describe, expect, it, vi } from "vitest"

import type { ClarificationQuestion } from "@/features/assistant/api/assistant-api"

vi.mock("@/components/ui/message-scroller", () => ({
  MessageScrollerItem: ({ children }: PropsWithChildren) => (
    <div>{children}</div>
  ),
}))

import { AssistantClarifications } from "@/features/assistant/components/assistant-clarifications"

const questions: ClarificationQuestion[] = [
  {
    id: "11111111-1111-4111-8111-111111111111",
    branchKey: "search:private-id:osb",
    sequenceNumber: 1,
    kind: "DIMENSIONS",
    prompt: "Какой размер бытовки с отделкой ОСБ нужен?",
    status: "PENDING",
    options: [
      {
        id: "22222222-2222-4222-8222-222222222222",
        label: "6x2.4",
        value: "6x2.4",
      },
      {
        id: "33333333-3333-4333-8333-333333333333",
        label: "8x2.4",
        value: "8x2.4",
      },
    ],
    answeredOptionId: null,
    createdAt: "2026-08-09T10:00:00Z",
    answeredAt: null,
  },
  {
    id: "44444444-4444-4444-8444-444444444444",
    branchKey: "search:private-id:ldsp",
    sequenceNumber: 2,
    kind: "CABIN_TYPE",
    prompt: "Какой тип бытовки с отделкой ЛДСП нужен?",
    status: "QUEUED",
    options: [
      {
        id: "55555555-5555-4555-8555-555555555555",
        label: "БК-1",
        value: "БК-1",
      },
      {
        id: "66666666-6666-4666-8666-666666666666",
        label: "Пост охраны",
        value: "Пост охраны",
      },
    ],
    answeredOptionId: null,
    createdAt: "2026-08-09T10:00:01Z",
    answeredAt: null,
  },
]

afterEach(cleanup)

describe("AssistantClarifications", () => {
  it("shows only the first sequential clarification and advances after its answer", async () => {
    const onAnswer = vi.fn()
    const user = userEvent.setup()
    const { rerender } = render(
      <AssistantClarifications
        questions={questions}
        disabled={false}
        onAnswer={onAnswer}
      />
    )

    expect(screen.getByText("Размер")).toBeTruthy()
    expect(screen.queryByText("Тип")).toBeNull()
    expect(screen.queryByText(/private-id/)).toBeNull()
    expect(
      screen.getByText("Какой размер бытовки с отделкой ОСБ нужен?")
    ).toBeTruthy()
    expect(
      screen.queryByText("Какой тип бытовки с отделкой ЛДСП нужен?")
    ).toBeNull()

    await user.click(screen.getByRole("radio", { name: "6x2.4" }))
    expect(onAnswer).toHaveBeenCalledWith(questions[0], questions[0].options[0])

    rerender(
      <AssistantClarifications
        disabled={false}
        questions={[
          {
            ...questions[0],
            status: "ANSWERED",
            answeredOptionId: questions[0].options[0].id,
            answeredAt: "2026-08-09T10:02:00Z",
          },
          { ...questions[1], status: "PENDING" },
        ]}
        onAnswer={onAnswer}
      />
    )

    expect(screen.queryByText("Размер")).toBeNull()
    expect(screen.getByText("Тип")).toBeTruthy()
    expect(
      screen.getByText("Какой тип бытовки с отделкой ЛДСП нужен?")
    ).toBeTruthy()
  })

  it("does not render answered, queued, or superseded clarification history", () => {
    render(
      <AssistantClarifications
        disabled={false}
        questions={[
          {
            ...questions[0],
            status: "ANSWERED",
            answeredOptionId: questions[0].options[0].id,
            answeredAt: "2026-08-09T10:02:00Z",
          },
          { ...questions[1], status: "SUPERSEDED" },
          {
            ...questions[1],
            id: "77777777-7777-4777-8777-777777777777",
            sequenceNumber: 3,
            status: "QUEUED",
          },
        ]}
        onAnswer={vi.fn()}
      />
    )

    expect(screen.queryByText("Отвечено")).toBeNull()
    expect(screen.queryByText("Выбрано: 6x2.4")).toBeNull()
    expect(screen.queryByText("Заменено")).toBeNull()
    expect(screen.queryAllByRole("radio")).toHaveLength(0)
  })
})
