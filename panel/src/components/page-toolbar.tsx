import type { ComponentProps } from "react"

import { cn } from "@/lib/utils"

function PageToolbar({ className, ...props }: ComponentProps<"header">) {
  return (
    <header
      data-slot="page-toolbar"
      className={cn(
        "flex min-h-9 w-full flex-col gap-3 sm:flex-row sm:items-center",
        className
      )}
      {...props}
    />
  )
}

function PageToolbarContent({ className, ...props }: ComponentProps<"div">) {
  return (
    <div
      data-slot="page-toolbar-content"
      className={cn("min-w-0 flex-1", className)}
      {...props}
    />
  )
}

function PageToolbarActions({ className, ...props }: ComponentProps<"div">) {
  return (
    <div
      data-slot="page-toolbar-actions"
      className={cn(
        "flex flex-wrap items-center gap-2 sm:ml-auto sm:justify-end",
        className
      )}
      {...props}
    />
  )
}

export { PageToolbar, PageToolbarActions, PageToolbarContent }
