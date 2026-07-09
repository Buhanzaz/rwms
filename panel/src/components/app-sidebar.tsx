import { useState, type MouseEvent } from "react"
import { Link, useLocation } from "react-router-dom"
import { useWarehouse } from "@/hooks/use-warehouse"
import {
  BarChart3,
  ChevronUp,
  ClipboardCheck,
  ClipboardList,
  ClipboardPenLine,
  Gauge,
  Hammer,
  Kanban,
  LayoutDashboard,
  PackageSearch,
  Settings,
  Warehouse,
  Wrench,
  type LucideIcon,
} from "lucide-react"

import {
  Combobox,
  ComboboxContent,
  ComboboxEmpty,
  ComboboxInput,
  ComboboxItem,
  ComboboxList,
} from "@/components/ui/combobox"

import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarMenuSub,
  SidebarMenuSubButton,
  SidebarMenuSubItem,
  SidebarRail,
} from "@/components/ui/sidebar"

type SidebarNavItem = {
  title: string
  url: string
  icon: LucideIcon
}

type SidebarNavGroup = {
  title: string
  items: SidebarNavItem[]
}

const navGroups: SidebarNavGroup[] = [
  {
    title: "Обзор",
    items: [
      {
        title: "Главная",
        url: "/",
        icon: LayoutDashboard,
      },
      {
        title: "KPI",
        url: "/kpi",
        icon: BarChart3,
      },
    ],
  },
  {
    title: "Имущество",
    items: [
      {
        title: "Склад",
        url: "/warehouse",
        icon: Warehouse,
      },
      {
        title: "Доп. оборудование",
        url: "/equipment",
        icon: PackageSearch,
      },
      {
        title: "Инвентаризация",
        url: "/inventory",
        icon: ClipboardCheck,
      },
    ],
  },
  {
    title: "Ремонтный цикл",
    items: [
      {
        title: "Сметы",
        url: "/estimates",
        icon: ClipboardList,
      },
      {
        title: "Ремонты",
        url: "/repairs",
        icon: Wrench,
      },
      {
        title: "Доска задач",
        url: "/task-board",
        icon: Gauge,
      },
      {
        title: "Приёмка и доработки",
        url: "/acceptance",
        icon: Hammer,
      },
    ],
  },
]

const settingsNavItems: SidebarNavItem[] = [
  {
    title: "Настройка смет и ремонтов",
    url: "/settings/estimates-repairs",
    icon: ClipboardPenLine,
  },
  {
    title: "Настройка Доски задач",
    url: "/settings/task-board",
    icon: Kanban,
  },
]

const sidebarButtonClass =
  "h-10 px-2 text-sm md:h-8 md:text-xs [&>svg]:!size-5 md:[&>svg]:!size-4"

const sidebarSubButtonClass =
  "h-10 w-full translate-x-0 px-2 text-sm md:h-8 md:text-xs [&>svg]:!size-5 md:[&>svg]:!size-4"

function isActiveUrl(currentPath: string, url: string) {
  if (url === "/") {
    return currentPath === "/"
  }

  return currentPath.startsWith(url)
}

function WarehouseSelector() {
  const { warehouses, selectedWarehouse, setSelectedWarehouseId } =
    useWarehouse()

  const warehouseCodes = warehouses.map((warehouse) => warehouse.code)

  function handleWarehouseChange(value: string | null) {
    if (value === null) {
      return
    }

    const warehouse = warehouses.find((warehouse) => warehouse.code === value)

    if (!warehouse) {
      return
    }

    setSelectedWarehouseId(warehouse.id)
  }

  return (
    <SidebarGroup className="p-0 pb-2 group-data-[collapsible=icon]:hidden">
      <SidebarGroupLabel>Склад</SidebarGroupLabel>

      <SidebarGroupContent>
        <Combobox
          items={warehouseCodes}
          value={selectedWarehouse?.code ?? ""}
          onValueChange={handleWarehouseChange}
        >
          <ComboboxInput
            placeholder="Выберите склад"
            className="h-10 w-full bg-background text-sm md:h-8 md:text-xs"
          />

          <ComboboxContent>
            <ComboboxEmpty>Склад не найден.</ComboboxEmpty>

            <ComboboxList>
              {(item) => (
                <ComboboxItem key={item} value={item}>
                  {item}
                </ComboboxItem>
              )}
            </ComboboxList>
          </ComboboxContent>
        </Combobox>
      </SidebarGroupContent>
    </SidebarGroup>
  )
}

export function AppSidebar() {
  const location = useLocation()
  const isSettingsActive = isActiveUrl(location.pathname, "/settings")
  const [settingsMenuOpen, setSettingsMenuOpen] = useState(false)

  function handleSidebarClick(event: MouseEvent<HTMLElement>) {
    const target = event.target

    if (!(target instanceof HTMLElement)) {
      return
    }

    if (target.closest("[data-settings-menu]")) {
      return
    }

    setSettingsMenuOpen(false)
  }

  return (
    <Sidebar collapsible="icon">
      <SidebarHeader
        className="h-16 shrink-0 border-b p-2"
        onClick={handleSidebarClick}
      >
        <SidebarMenu>
          <SidebarMenuItem>
            <SidebarMenuButton size="lg" className="h-12 px-2" asChild>
              <Link to="/">
                <div className="flex aspect-square size-8 items-center justify-center rounded-lg bg-sidebar-primary text-sidebar-primary-foreground">
                  <Warehouse className="size-4" />
                </div>

                <div className="grid flex-1 text-left text-sm leading-tight">
                  <span className="truncate font-semibold">WMS Panel</span>
                  <span className="truncate text-xs">Складская система</span>
                </div>
              </Link>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarHeader>

      <SidebarContent
        className="gap-0 px-2 py-2"
        onClick={handleSidebarClick}
      >
        <WarehouseSelector />

        {navGroups.map((group) => (
          <SidebarGroup key={group.title} className="p-0">
            <SidebarGroupLabel className="h-7 px-2 text-xs">
              {group.title}
            </SidebarGroupLabel>

            <SidebarGroupContent>
              <SidebarMenu className="gap-0.5">
                {group.items.map((item) => {
                  const Icon = item.icon

                  return (
                    <SidebarMenuItem key={item.title}>
                      <SidebarMenuButton
                        asChild
                        className={sidebarButtonClass}
                        isActive={isActiveUrl(location.pathname, item.url)}
                      >
                        <Link to={item.url}>
                          <Icon />
                          <span>{item.title}</span>
                        </Link>
                      </SidebarMenuButton>
                    </SidebarMenuItem>
                  )
                })}
              </SidebarMenu>
            </SidebarGroupContent>
          </SidebarGroup>
        ))}
      </SidebarContent>

      <SidebarFooter className="p-2" data-settings-menu>
        <SidebarMenu>
          {settingsMenuOpen && (
            <SidebarMenuSub
              id="settings-submenu"
              className="mx-0 mb-1 w-full translate-x-0 border-l-0 px-0 group-data-[collapsible=icon]:hidden"
            >
              {settingsNavItems.map((item) => {
                const Icon = item.icon

                return (
                  <SidebarMenuSubItem key={item.title}>
                    <SidebarMenuSubButton
                      asChild
                      isActive={isActiveUrl(location.pathname, item.url)}
                      className={sidebarSubButtonClass}
                    >
                      <Link
                        to={item.url}
                        onClick={() => setSettingsMenuOpen(false)}
                      >
                        <Icon />
                        <span>{item.title}</span>
                      </Link>
                    </SidebarMenuSubButton>
                  </SidebarMenuSubItem>
                )
              })}
            </SidebarMenuSub>
          )}

          <SidebarMenuItem>
            <SidebarMenuButton
              type="button"
              className={sidebarButtonClass}
              isActive={settingsMenuOpen || isSettingsActive}
              aria-controls="settings-submenu"
              aria-expanded={settingsMenuOpen}
              onClick={() => setSettingsMenuOpen((current) => !current)}
            >
              <Settings />
              <span>Настройки</span>
              <ChevronUp className="ml-auto" />
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarFooter>

      <SidebarRail />
    </Sidebar>
  )
}
