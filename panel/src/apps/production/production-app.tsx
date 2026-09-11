import { useEffect, useRef, type CSSProperties } from "react"
import {
  BoxIcon,
  ClipboardCheckIcon,
  DashboardSquare01Icon,
  HammerIcon,
  Task01Icon,
  WarehouseIcon,
} from "@hugeicons/core-free-icons"
import type { IconSvgElement } from "@hugeicons/react"
import { Navigate, Route, Routes, useLocation } from "react-router-dom"

import { AnimatedThemeToggler } from "@/components/ui/animated-theme-toggler"
import {
  PanelSidebarMenuLink,
  PanelSidebarShell,
} from "@/components/panel-sidebar-shell"
import {
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarInset,
  SidebarMenu,
  SidebarProvider,
} from "@/components/ui/sidebar"
import { useAuth } from "@/features/auth/use-auth"

type ProductionNavigationItem = {
  title: string
  to: string
  icon: IconSvgElement
}

type ProductionNavigationGroup = {
  title: string
  items: readonly ProductionNavigationItem[]
}

const navigationGroups: readonly ProductionNavigationGroup[] = [
  {
    title: "Обзор",
    items: [
      { title: "Главная", to: "/production", icon: DashboardSquare01Icon },
      {
        title: "Работа с претензиями",
        to: "/production/claims",
        icon: ClipboardCheckIcon,
      },
    ],
  },
  {
    title: "Производство",
    items: [
      { title: "СAD online", to: "/production/cad", icon: BoxIcon },
      { title: "Заказы", to: "/production/orders", icon: Task01Icon },
      {
        title: "Кап. ремонты",
        to: "/production/capital-repairs",
        icon: HammerIcon,
      },
      {
        title: "Доска задач",
        to: "/production/task-board",
        icon: ClipboardCheckIcon,
      },
    ],
  },
  {
    title: "Имущество",
    items: [
      { title: "Склад", to: "/production/warehouse", icon: WarehouseIcon },
    ],
  },
]

const navigationItems = navigationGroups.flatMap((group) => group.items)
const backgroundVideoUrl = "/admin/background.webm"

function isActive(pathname: string, to: string) {
  if (to === "/production") {
    return pathname === to || pathname === `${to}/`
  }

  return pathname === to || pathname.startsWith(`${to}/`)
}

function currentTitle(pathname: string) {
  return (
    navigationItems.find((item) => isActive(pathname, item.to))?.title ??
    "Производство"
  )
}

function ProductionNavigation() {
  const { pathname } = useLocation()

  return (
    <nav aria-label="Разделы производства" className="flex flex-col gap-1">
      {navigationGroups.map((group) => (
        <SidebarGroup key={group.title}>
          <SidebarGroupLabel>{group.title}</SidebarGroupLabel>
          <SidebarGroupContent>
            <SidebarMenu>
              {group.items.map((item) => (
                <PanelSidebarMenuLink
                  key={item.to}
                  title={item.title}
                  to={item.to}
                  icon={item.icon}
                  isActive={isActive(pathname, item.to)}
                />
              ))}
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>
      ))}
    </nav>
  )
}

function EmptyProductionPage() {
  return <section aria-label="Содержимое раздела производства" />
}

function ProductionLayout() {
  const { pathname } = useLocation()
  const { logout } = useAuth()
  const backgroundVideoRef = useRef<HTMLVideoElement>(null)
  const title = currentTitle(pathname)

  useEffect(() => {
    if (typeof window.matchMedia !== "function") return

    const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)")
    const stopForReducedMotion = () => {
      const video = backgroundVideoRef.current
      if (reducedMotion.matches && video && !video.paused) video.pause()
    }
    stopForReducedMotion()
    reducedMotion.addEventListener("change", stopForReducedMotion)
    return () =>
      reducedMotion.removeEventListener("change", stopForReducedMotion)
  }, [])

  return (
    <div className="relative isolate h-svh overflow-hidden bg-background">
      <a
        href="#production-content"
        className="sr-only z-50 rounded-md bg-background px-3 py-2 text-sm font-medium shadow-lg focus:not-sr-only focus:fixed focus:top-3 focus:left-3 focus-visible:ring-2 focus-visible:ring-ring"
      >
        К основному содержимому
      </a>
      <video
        ref={backgroundVideoRef}
        aria-hidden="true"
        tabIndex={-1}
        autoPlay
        loop
        muted
        playsInline
        preload="metadata"
        onLoadedMetadata={(event) => {
          event.currentTarget.playbackRate = 0.5
        }}
        className="pointer-events-none absolute inset-0 -z-20 size-full object-cover motion-reduce:hidden"
      >
        <source src={backgroundVideoUrl} type="video/webm" />
      </video>
      <div
        aria-hidden="true"
        className="pointer-events-none absolute inset-0 -z-10 bg-background/42 backdrop-blur-[2px] dark:bg-background/72"
      />

      <SidebarProvider
        style={{ "--sidebar-width": "15rem" } as CSSProperties}
        className="h-svh min-h-0 gap-2 bg-transparent p-2"
      >
        <PanelSidebarShell
          collapsible="none"
          className="rwms-shell-surface rounded-2xl bg-shell-surface"
          ariaLabel="BLOCKBOX: Производство"
          caption="Производство"
          brandTo="/production"
          onLogout={() => void logout()}
        >
          <ProductionNavigation />
        </PanelSidebarShell>

        <SidebarInset
          id="production-content"
          tabIndex={-1}
          className="rwms-shell-surface min-h-0 overflow-hidden rounded-2xl bg-shell-surface"
        >
          <header className="flex h-14 shrink-0 items-center gap-3 border-b border-border px-4">
            <div className="min-w-0">
              <p className="truncate text-xs text-muted-foreground">
                Производство
              </p>
              <p className="truncate text-sm font-medium">{title}</p>
            </div>
            <AnimatedThemeToggler className="ml-auto" />
          </header>
          <div className="min-h-0 flex-1 overflow-auto">
            <div className="min-h-full w-full p-4 lg:p-5">
              <Routes>
                {navigationItems.map((item) => (
                  <Route
                    key={item.to}
                    path={item.to}
                    element={<EmptyProductionPage />}
                  />
                ))}
                <Route
                  path="*"
                  element={<Navigate to="/production" replace />}
                />
              </Routes>
            </div>
          </div>
        </SidebarInset>
      </SidebarProvider>
    </div>
  )
}

export function ProductionApp() {
  return <ProductionLayout />
}
