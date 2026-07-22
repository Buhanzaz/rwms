import type { ReactNode } from "react"

import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card"
import { cn } from "@/lib/utils"

type WorkspacePanelProps = {
  title?: string
  description?: string
  action?: ReactNode
  children: ReactNode
  className?: string
  contentClassName?: string
}

function WorkspacePanel({
  title,
  description,
  action,
  children,
  className,
  contentClassName,
}: WorkspacePanelProps) {
  return (
    <Card className={cn("h-full min-h-0 ring-inset", className)}>
      {title ? (
        <CardHeader>
          <CardTitle>{title}</CardTitle>
          {description ? (
            <CardDescription>{description}</CardDescription>
          ) : null}
          {action ? <CardAction>{action}</CardAction> : null}
        </CardHeader>
      ) : null}
      <CardContent
        className={cn("flex min-h-0 flex-1 flex-col", contentClassName)}
      >
        {children}
      </CardContent>
    </Card>
  )
}

type RepairEstimateWorkspaceLayoutProps = {
  ariaLabel?: string
  informationDescription?: string
  catalogDescription?: string
  message?: ReactNode
  photos: ReactNode
  information: ReactNode
  estimate: ReactNode
  controls: ReactNode
}

export function RepairEstimateWorkspaceLayout({
  ariaLabel = "Редактор сметы",
  informationDescription = "Заполните бытовку, отправителя, дату прибытия и общий комментарий.",
  catalogDescription = "Выберите работу или материал для добавления в смету.",
  message,
  photos,
  information,
  estimate,
  controls,
}: RepairEstimateWorkspaceLayoutProps) {
  return (
    <section
      aria-label={ariaLabel}
      className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto xl:overflow-hidden"
    >
      {message}

      <div className="grid min-h-0 flex-1 grid-cols-1 gap-3 p-px xl:grid-cols-[minmax(0,12fr)_minmax(0,8fr)] xl:grid-rows-[minmax(15rem,0.9fr)_minmax(18rem,1.1fr)]">
        <WorkspacePanel
          className="order-2 xl:col-start-1 xl:row-start-1"
          contentClassName="overflow-hidden"
        >
          {photos}
        </WorkspacePanel>

        <WorkspacePanel
          className="order-3 xl:col-start-1 xl:row-start-2"
          contentClassName="overflow-hidden"
        >
          {estimate}
        </WorkspacePanel>

        <WorkspacePanel
          title="Информация"
          description={informationDescription}
          className="order-1 xl:col-start-2 xl:row-start-1"
          contentClassName="overflow-y-auto"
        >
          {information}
        </WorkspacePanel>

        <WorkspacePanel
          title="Каталог"
          description={catalogDescription}
          className="order-4 xl:col-start-2 xl:row-start-2"
          contentClassName="overflow-y-auto"
        >
          {controls}
        </WorkspacePanel>
      </div>
    </section>
  )
}

type RepairWorkDetailWorkspaceLayoutProps = {
  ariaLabel: string
  mobileContentFlow?: boolean
  message?: ReactNode
  photos: ReactNode
  information: ReactNode
  informationDescription?: string
  informationAction?: ReactNode
  lowerTitle: string
  lowerDescription?: string
  lowerAction?: ReactNode
  lowerContent: ReactNode
}

export function RepairWorkDetailWorkspaceLayout({
  ariaLabel,
  mobileContentFlow = false,
  message,
  photos,
  information,
  informationDescription,
  informationAction,
  lowerTitle,
  lowerDescription,
  lowerAction,
  lowerContent,
}: RepairWorkDetailWorkspaceLayoutProps) {
  return (
    <section
      aria-label={ariaLabel}
      className="flex min-h-0 flex-1 flex-col gap-3 overflow-y-auto xl:overflow-hidden"
    >
      {message}

      <div
        className={cn(
          "grid min-h-0 grid-cols-1 gap-3 p-px xl:grid-cols-[minmax(0,12fr)_minmax(0,8fr)] xl:grid-rows-[minmax(15rem,0.8fr)_minmax(18rem,1.2fr)]",
          mobileContentFlow ? "flex-none xl:flex-1" : "flex-1"
        )}
      >
        <WorkspacePanel
          className="order-1 xl:col-start-1 xl:row-start-1"
          contentClassName="overflow-hidden"
        >
          {photos}
        </WorkspacePanel>

        <WorkspacePanel
          title="Информация"
          description={informationDescription}
          action={informationAction}
          className="order-2 xl:col-start-2 xl:row-start-1"
          contentClassName="overflow-y-auto"
        >
          {information}
        </WorkspacePanel>

        <WorkspacePanel
          title={lowerTitle}
          description={lowerDescription}
          action={lowerAction}
          className={cn(
            "order-3 xl:col-span-2 xl:col-start-1 xl:row-start-2",
            mobileContentFlow && "overflow-visible xl:overflow-hidden"
          )}
          contentClassName={cn(
            mobileContentFlow
              ? "overflow-visible xl:overflow-y-auto"
              : "overflow-y-auto"
          )}
        >
          {lowerContent}
        </WorkspacePanel>
      </div>
    </section>
  )
}
