import type { ComponentProps, ReactNode } from "react"
import { Logout03Icon } from "@hugeicons/core-free-icons"
import { HugeiconsIcon, type IconSvgElement } from "@hugeicons/react"
import { Link } from "react-router-dom"

import logotypeUrl from "../../../Logotype.svg"

import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
} from "@/components/ui/sidebar"
import { Text3DFlip } from "@/components/ui/text-3d-flip"

type PanelSidebarShellProps = ComponentProps<typeof Sidebar> & {
  ariaLabel: string
  caption: string
  brandTo?: string
  onBrandClick?: () => void
  onLogout: () => void
  children: ReactNode
}

type PanelSidebarMenuLinkProps = {
  title: string
  to: string
  icon: IconSvgElement
  isActive: boolean
  onNavigate?: () => void
}

function PanelBrandWordmark() {
  return (
    <Text3DFlip
      as="span"
      className="h-[15px] items-start font-sans text-[15px] leading-none font-black tracking-[0.045em] whitespace-nowrap text-[#204B79] dark:text-[#549AC5]"
      textClassName="leading-none"
      flipTextClassName="leading-none"
      rotateDirection="top"
      staggerDuration={0.03}
      staggerFrom="first"
      transition={{ type: "spring", damping: 25, stiffness: 160 }}
    >
      BLOCKBOX
    </Text3DFlip>
  )
}

export function PanelSidebarMenuLink({
  title,
  to,
  icon,
  isActive,
  onNavigate,
}: PanelSidebarMenuLinkProps) {
  return (
    <SidebarMenuItem>
      <SidebarMenuButton
        asChild
        isActive={isActive}
        tooltip={title}
        className="h-9"
      >
        <Link to={to} onClick={onNavigate}>
          <span className="flex size-8 shrink-0 items-center justify-center group-data-[collapsible=icon]:size-9">
            <HugeiconsIcon icon={icon} strokeWidth={2} />
          </span>
          <span>{title}</span>
        </Link>
      </SidebarMenuButton>
    </SidebarMenuItem>
  )
}

export function PanelSidebarShell({
  ariaLabel,
  caption,
  brandTo,
  onBrandClick,
  onLogout,
  children,
  collapsible = "none",
  ...props
}: PanelSidebarShellProps) {
  const brand = (
    <>
      <img
        src={logotypeUrl}
        alt=""
        className="size-8 shrink-0 object-contain"
      />
      <span className="flex h-8 w-[7.25rem] shrink-0 flex-col items-start justify-between">
        <PanelBrandWordmark />
        <span className="block text-left text-xs leading-none whitespace-nowrap text-muted-foreground">
          {caption}
        </span>
      </span>
    </>
  )

  return (
    <Sidebar {...props} collapsible={collapsible}>
      <SidebarHeader className="h-14 shrink-0 border-b border-sidebar-border px-3 py-0">
        {brandTo ? (
          <Link
            to={brandTo}
            aria-label={ariaLabel}
            className="flex h-full w-full items-center justify-center gap-2"
            onClick={onBrandClick}
          >
            {brand}
          </Link>
        ) : (
          <div
            aria-label={ariaLabel}
            className="flex h-full w-full items-center justify-center gap-2"
          >
            {brand}
          </div>
        )}
      </SidebarHeader>

      <SidebarContent className="gap-1 px-1 pb-2">{children}</SidebarContent>

      <SidebarFooter className="px-3 py-2">
        <SidebarMenu>
          <SidebarMenuItem>
            <SidebarMenuButton
              type="button"
              tooltip="Выйти"
              className="h-9"
              onClick={onLogout}
            >
              <HugeiconsIcon icon={Logout03Icon} aria-hidden="true" />
              <span>Выйти</span>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarFooter>
    </Sidebar>
  )
}
