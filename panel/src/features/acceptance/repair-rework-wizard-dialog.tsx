import { useMemo, useState } from "react"
import { useNavigate } from "react-router-dom"

import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import {
  Field,
  FieldDescription,
  FieldGroup,
  FieldLabel,
  FieldLegend,
  FieldSet,
} from "@/components/ui/field"
import {
  ToggleGroup,
  ToggleGroupItem,
} from "@/components/ui/toggle-group"
import { cloneRepairLineForRework } from "@/features/repair-tasks/domain/repair-task-domain"
import type {
  RepairTaskDto,
  RepairTaskSubtaskDto,
  RepairsLocationState,
} from "@/features/repair-tasks/model/repair-task"
import type { RepairEstimateLineDto } from "@/features/repair-estimates/model/repair-estimate"

type ReworkRecipient = "TARGETED" | "GENERAL"

type RepairReworkWizardDialogProps = {
  open: boolean
  task: RepairTaskDto
  selectedIds: Set<string>
  onOpenChange: (open: boolean) => void
}

function isSubtaskSelected(
  subtask: RepairTaskSubtaskDto,
  selectedIds: Set<string>
) {
  if (selectedIds.has(`group:${subtask.id}`)) {
    return true
  }
  return subtask.assignments.some((assignment) =>
    selectedIds.has(assignment.id)
  )
}

function lineIdentity(line: RepairEstimateLineDto) {
  return line.sourceLineKey.trim() || line.id
}

export function RepairReworkWizardDialog({
  open,
  task,
  selectedIds,
  onOpenChange,
}: RepairReworkWizardDialogProps) {
  const navigate = useNavigate()
  const [step, setStep] = useState<1 | 2>(1)
  const [recipient, setRecipient] = useState<ReworkRecipient | "">("")
  const [selectedLineKeys, setSelectedLineKeys] = useState<Set<string>>(
    () => new Set()
  )

  const selectedSubtasks = useMemo(
    () =>
      task.subtasks.filter((subtask) =>
        isSubtaskSelected(subtask, selectedIds)
      ),
    [selectedIds, task.subtasks]
  )
  const selectedAssignments = useMemo(
    () =>
      selectedSubtasks.flatMap((subtask) =>
        subtask.assignments.filter((assignment) =>
          selectedIds.has(assignment.id)
        )
      ),
    [selectedIds, selectedSubtasks]
  )
  const historicalGroups = new Map(
    selectedSubtasks
      .filter((subtask) => subtask.workerGroup)
      .map((subtask) => [subtask.workerGroup!.id, subtask.workerGroup!])
  )
  const targetedAvailable =
    selectedSubtasks.length > 0 &&
    historicalGroups.size === 1 &&
    selectedSubtasks.every((subtask) => subtask.workerGroup !== null)
  const groupExplicitlySelected = selectedSubtasks.some((subtask) =>
    selectedIds.has(`group:${subtask.id}`)
  )
  const targetedLabel =
    !groupExplicitlySelected &&
    selectedAssignments.length === 1 &&
    selectedAssignments[0].worker
      ? `Работнику: ${selectedAssignments[0].worker.name}`
      : historicalGroups.size === 1
        ? `Группе: ${Array.from(historicalGroups.values())[0].name}`
        : "Выбранной группе/работнику"

  const availableLines = useMemo(() => {
    const unique = new Map<string, RepairEstimateLineDto>()
    selectedSubtasks.forEach((subtask) => {
      ;[...subtask.workLines, ...subtask.materialLines].forEach((line) => {
        const key = lineIdentity(line)
        if (!unique.has(key)) unique.set(key, line)
      })
    })
    return Array.from(unique.entries()).map(([key, line]) => ({ key, line }))
  }, [selectedSubtasks])

  function resetAndClose() {
    setStep(1)
    setRecipient("")
    setSelectedLineKeys(new Set())
    onOpenChange(false)
  }

  function openEditor() {
    const lines = availableLines
      .filter(({ key }) => selectedLineKeys.has(key))
      .map(({ line }) => cloneRepairLineForRework(line))
    const state: RepairsLocationState = {
      workspaceEntry: true,
      reworkSeed: {
        type: "repair-rework-seed-v1",
        warehouseId: task.warehouseId,
        sourceRepairTaskId: task.id,
        sourceRepairTaskVersion: task.version,
        sourceOrigin: task.origin,
        sourceEstimateId: task.sourceEstimateId,
        sourceEstimateVersion: task.sourceEstimateVersion,
        rentalItemId: task.rentalItemId,
        lines,
      },
    }
    resetAndClose()
    navigate("/repairs?create=1", { state })
  }

  const workLines = availableLines.filter(
    ({ line }) => line.lineType === "WORK"
  )
  const materialLines = availableLines.filter(
    ({ line }) => line.lineType === "MATERIAL"
  )

  return (
    <Dialog
      open={open}
      onOpenChange={(nextOpen) => {
        if (!nextOpen) resetAndClose()
      }}
    >
      <DialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Переделать</DialogTitle>
          <DialogDescription>
            {step === 1
              ? "Это предварительный выбор маршрута. Окончательная очередь выбирается при завершении задания, исполнители пока не назначаются."
              : "Выберите работы и материалы, которые нужно повторить. Можно продолжить без выбора."}
          </DialogDescription>
        </DialogHeader>

        {step === 1 ? (
          <FieldSet>
            <FieldLegend>Кому отправить доработку?</FieldLegend>
            <FieldDescription>
              В общей очереди задание смогут взять все допустимые исполнители.
            </FieldDescription>
            <ToggleGroup
              type="single"
              value={recipient}
              variant="outline"
              orientation="vertical"
              className="w-full items-stretch"
              onValueChange={(value) =>
                setRecipient(value as ReworkRecipient | "")
              }
            >
              <ToggleGroupItem
                value="TARGETED"
                disabled={!targetedAvailable}
                className="h-auto min-h-10 justify-start whitespace-normal px-3 py-2 text-left"
              >
                {targetedLabel}
              </ToggleGroupItem>
              <ToggleGroupItem
                value="GENERAL"
                className="h-auto min-h-10 justify-start whitespace-normal px-3 py-2 text-left"
              >
                В общую очередь
              </ToggleGroupItem>
            </ToggleGroup>
            {!targetedAvailable ? (
              <FieldDescription>
                Адресная отправка доступна, когда выбор относится к одной
                рабочей группе. Сейчас можно отправить только в общую очередь.
              </FieldDescription>
            ) : null}
          </FieldSet>
        ) : (
          <FieldGroup>
            <LineSelectionFieldSet
              legend="Работы"
              items={workLines}
              selectedKeys={selectedLineKeys}
              onToggle={(key, checked) =>
                setSelectedLineKeys((current) => {
                  const next = new Set(current)
                  if (checked) next.add(key)
                  else next.delete(key)
                  return next
                })
              }
            />
            <LineSelectionFieldSet
              legend="Материалы"
              items={materialLines}
              selectedKeys={selectedLineKeys}
              onToggle={(key, checked) =>
                setSelectedLineKeys((current) => {
                  const next = new Set(current)
                  if (checked) next.add(key)
                  else next.delete(key)
                  return next
                })
              }
            />
          </FieldGroup>
        )}

        <DialogFooter>
          <Button
            type="button"
            variant="outline"
            onClick={() => (step === 1 ? resetAndClose() : setStep(1))}
          >
            {step === 1 ? "Отмена" : "Назад"}
          </Button>
          <Button
            type="button"
            disabled={step === 1 && !recipient}
            onClick={() => (step === 1 ? setStep(2) : openEditor())}
          >
            Далее
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function LineSelectionFieldSet({
  legend,
  items,
  selectedKeys,
  onToggle,
}: {
  legend: string
  items: Array<{ key: string; line: RepairEstimateLineDto }>
  selectedKeys: Set<string>
  onToggle: (key: string, checked: boolean) => void
}) {
  return (
    <FieldSet>
      <FieldLegend>{legend}</FieldLegend>
      {items.length === 0 ? (
        <FieldDescription>Позиций нет.</FieldDescription>
      ) : (
        <FieldGroup className="gap-3">
          {items.map(({ key, line }) => {
            const id = `rework-line-${line.id}`
            return (
              <Field key={key} orientation="horizontal">
                <Checkbox
                  id={id}
                  checked={selectedKeys.has(key)}
                  onCheckedChange={(checked) => onToggle(key, checked === true)}
                />
                <FieldLabel htmlFor={id} className="min-w-0 font-normal">
                  <span className="flex min-w-0 flex-col gap-1">
                    <span>{line.description}</span>
                    {line.lineComment ? (
                      <span className="text-xs text-muted-foreground">
                        {line.lineComment}
                      </span>
                    ) : null}
                  </span>
                </FieldLabel>
              </Field>
            )
          })}
        </FieldGroup>
      )}
    </FieldSet>
  )
}
