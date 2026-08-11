import { AiChat02Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"

import { Badge } from "@/components/ui/badge"
import { Bubble, BubbleContent } from "@/components/ui/bubble"
import { Button } from "@/components/ui/button"
import {
  Message,
  MessageAvatar,
  MessageContent,
  MessageHeader,
} from "@/components/ui/message"
import { MessageScrollerItem } from "@/components/ui/message-scroller"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import type {
  ClarificationOption,
  ClarificationQuestion,
} from "@/features/assistant/api/assistant-api"

const STATUS_LABELS: Record<ClarificationQuestion["status"], string> = {
  QUEUED: "В очереди",
  PENDING: "Ждёт ответа",
  ANSWERED: "Отвечено",
  SUPERSEDED: "Заменено",
}

const KIND_LABELS: Record<ClarificationQuestion["kind"], string> = {
  CABIN_TYPE: "Тип",
  FINISH: "Отделка",
  DIMENSIONS: "Размер",
  CATEGORY: "Категория",
  SEARCH_MERGE: "Режим подборки",
}

function ClarificationOptions({
  question,
  disabled,
  onAnswer,
}: {
  question: ClarificationQuestion
  disabled: boolean
  onAnswer: (option: ClarificationOption) => void
}) {
  const answeredValue = question.answeredOptionId ?? ""
  if (question.options.length >= 2 && question.options.length <= 7) {
    return (
      <ToggleGroup
        type="single"
        value={answeredValue}
        variant="outline"
        size="sm"
        disabled={disabled}
        className="max-w-full flex-wrap justify-start"
        aria-label={question.prompt}
        onValueChange={(optionId) => {
          const option = question.options.find(
            (candidate) => candidate.id === optionId
          )
          if (option) onAnswer(option)
        }}
      >
        {question.options.map((option) => (
          <ToggleGroupItem key={option.id} value={option.id}>
            {option.label}
          </ToggleGroupItem>
        ))}
      </ToggleGroup>
    )
  }

  return (
    <div className="flex flex-wrap gap-2">
      {question.options.map((option) => (
        <Button
          key={option.id}
          type="button"
          size="sm"
          variant={
            question.answeredOptionId === option.id ? "secondary" : "outline"
          }
          disabled={disabled}
          onClick={() => onAnswer(option)}
        >
          {option.label}
        </Button>
      ))}
    </div>
  )
}

function ClarificationCard({
  question,
  disabled,
  onAnswer,
}: {
  question: ClarificationQuestion
  disabled: boolean
  onAnswer: (option: ClarificationOption) => void
}) {
  const pending = question.status === "PENDING"
  const answeredOption = question.options.find(
    (option) => option.id === question.answeredOptionId
  )
  return (
    <Message align="start">
      <MessageAvatar className="size-8 bg-primary/10 text-primary">
        <HugeiconsIcon icon={AiChat02Icon} />
      </MessageAvatar>
      <MessageContent>
        <MessageHeader>Ассистент · уточнение</MessageHeader>
        <Bubble variant={pending ? "outline" : "muted"} className="max-w-full">
          <BubbleContent className="flex w-full min-w-0 flex-col gap-3 p-3">
            <div className="flex flex-wrap items-center gap-2">
              <Badge variant={pending ? "default" : "outline"}>
                {STATUS_LABELS[question.status]}
              </Badge>
              <Badge variant="secondary">{KIND_LABELS[question.kind]}</Badge>
            </div>
            <p className="text-sm font-medium">{question.prompt}</p>
            <ClarificationOptions
              question={question}
              disabled={disabled || !pending}
              onAnswer={onAnswer}
            />
            {answeredOption ? (
              <p className="text-xs text-muted-foreground">
                Выбрано: {answeredOption.label}
              </p>
            ) : null}
          </BubbleContent>
        </Bubble>
      </MessageContent>
    </Message>
  )
}

export function AssistantClarifications({
  questions,
  disabled,
  onAnswer,
}: {
  questions: ClarificationQuestion[]
  disabled: boolean
  onAnswer: (
    question: ClarificationQuestion,
    option: ClarificationOption
  ) => void
}) {
  const pendingQuestion = [...questions]
    .filter((question) => question.status === "PENDING")
    .sort((left, right) => left.sequenceNumber - right.sequenceNumber)[0]

  return pendingQuestion ? (
    <MessageScrollerItem
      key={`clarification:${pendingQuestion.id}`}
      messageId={`clarification:${pendingQuestion.id}`}
      className="mx-auto w-full max-w-4xl"
    >
      <ClarificationCard
        question={pendingQuestion}
        disabled={disabled}
        onAnswer={(option) => onAnswer(pendingQuestion, option)}
      />
    </MessageScrollerItem>
  ) : null
}
