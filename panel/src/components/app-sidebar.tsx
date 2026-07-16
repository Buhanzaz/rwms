import { useState, type ComponentProps } from "react"
import { Link, useLocation } from "react-router-dom"
import { HugeiconsIcon, type IconSvgElement } from "@hugeicons/react"
import {
  ArrowDown01Icon,
  ArrowRight01Icon,
  ChartIncreaseIcon,
  ClipboardCheckIcon,
  ClipboardListIcon,
  ClipboardPenLineIcon,
  DeliveryReturn01Icon,
  DeliverySent01Icon,
  Exchange01Icon,
  DashboardSquare01Icon,
  GaugeIcon,
  HammerIcon,
  KanbanIcon,
  Logout03Icon,
  PackageSearchIcon,
  Settings02Icon,
  UserGroupIcon,
  WarehouseIcon,
  WasteIcon,
  Wrench01Icon,
} from "@hugeicons/core-free-icons"
import { useIsTabletOrSmaller } from "@/hooks/use-mobile"
import { useWarehouse } from "@/hooks/use-warehouse"
import { isGlobalAdministrator } from "@/features/auth/auth-model"
import { useAuth } from "@/features/auth/use-auth"

import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"

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
  useSidebar,
} from "@/components/ui/sidebar"

type SidebarNavItem = {
  title: string
  url: string
  icon: IconSvgElement
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
        icon: DashboardSquare01Icon,
      },
      {
        title: "KPI",
        url: "/kpi",
        icon: ChartIncreaseIcon,
      },
    ],
  },
  {
    title: "Имущество",
    items: [
      {
        title: "Склад",
        url: "/warehouse",
        icon: WarehouseIcon,
      },
      {
        title: "Доп. оборудование",
        url: "/equipment",
        icon: PackageSearchIcon,
      },
      {
        title: "Инвентаризация",
        url: "/inventory",
        icon: ClipboardCheckIcon,
      },
    ],
  },
  {
    title: "Логистика",
    items: [
      {
        title: "Возврат из аренды",
        url: "/logistics/returns",
        icon: DeliveryReturn01Icon,
      },
      {
        title: "Отгрузка в аренду",
        url: "/logistics/shipments",
        icon: DeliverySent01Icon,
      },
      {
        title: "Перемещения",
        url: "/logistics/transfers",
        icon: Exchange01Icon,
      },
    ],
  },
  {
    title: "Ремонтный цикл",
    items: [
      {
        title: "Сметы",
        url: "/estimates",
        icon: ClipboardListIcon,
      },
      {
        title: "Ремонты",
        url: "/repairs",
        icon: Wrench01Icon,
      },
      {
        title: "Доска задач",
        url: "/task-board",
        icon: GaugeIcon,
      },
      {
        title: "Приёмка и доработки",
        url: "/acceptance",
        icon: HammerIcon,
      },
    ],
  },
]

const settingsNavItems: SidebarNavItem[] = [
  {
    title: "Склады",
    url: "/settings/warehouses",
    icon: WarehouseIcon,
  },
  {
    title: "Пользователи",
    url: "/settings/users",
    icon: UserGroupIcon,
  },
  {
    title: "Настройка смет и ремонтов",
    url: "/settings/estimates-repairs",
    icon: ClipboardPenLineIcon,
  },
  {
    title: "Настройка Доски задач",
    url: "/settings/task-board",
    icon: KanbanIcon,
  },
  {
    title: "Имущество",
    url: "/settings/assets",
    icon: PackageSearchIcon,
  },
]

const writeOffNavItems: SidebarNavItem[] = [
  {
    title: "Склад",
    url: "/write-offs",
    icon: WarehouseIcon,
  },
  {
    title: "Доп. оборудование",
    url: "/write-offs/equipment",
    icon: PackageSearchIcon,
  },
]

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

  function handleWarehouseChange(value: string) {
    const warehouse = warehouses.find((warehouse) => warehouse.code === value)

    if (!warehouse) {
      return
    }

    setSelectedWarehouseId(warehouse.id)
  }

  return (
    <SidebarGroup className="pt-6 group-data-[collapsible=icon]:hidden">
      <SidebarGroupContent>
        <Select
          value={selectedWarehouse?.code ?? ""}
          onValueChange={handleWarehouseChange}
        >
          <SelectTrigger aria-label="Выбор склада" className="w-full">
            <SelectValue placeholder="Выберите склад" />
          </SelectTrigger>

          <SelectContent position="popper">
            <SelectGroup>
              {warehouseCodes.map((item) => (
                <SelectItem key={item} value={item}>
                  {item}
                </SelectItem>
              ))}
            </SelectGroup>
          </SelectContent>
        </Select>
      </SidebarGroupContent>
    </SidebarGroup>
  )
}

function WriteOffsSidebarMenu({
  currentPath,
  onOpen,
  onNavigate,
}: {
  currentPath: string
  onOpen: () => void
  onNavigate: () => void
}) {
  const isWriteOffsActive = isActiveUrl(currentPath, "/write-offs")
  const [menuOpen, setMenuOpen] = useState(isWriteOffsActive)

  function toggleMenu() {
    const next = !menuOpen

    if (next) {
      onOpen()
    }

    setMenuOpen(next)
  }

  return (
    <SidebarMenuItem>
      <SidebarMenuButton
        type="button"
        isActive={menuOpen || isWriteOffsActive}
        aria-controls="write-offs-submenu"
        aria-expanded={menuOpen}
        onClick={toggleMenu}
        className="h-10 py-1"
      >
        <span className="flex size-8 shrink-0 items-center justify-center">
          <HugeiconsIcon icon={WasteIcon} strokeWidth={2} />
        </span>
        <span>Списание</span>
        <HugeiconsIcon
          icon={menuOpen ? ArrowDown01Icon : ArrowRight01Icon}
          strokeWidth={2}
          className="ml-auto transition-transform"
        />
      </SidebarMenuButton>

      {menuOpen ? (
        <SidebarMenuSub id="write-offs-submenu">
          {writeOffNavItems.map((item) => {
            const isActive =
              item.url === "/write-offs"
                ? currentPath === item.url
                : currentPath.startsWith(item.url)

            return (
              <SidebarMenuSubItem key={item.title}>
                <SidebarMenuSubButton
                  asChild
                  size="md"
                  isActive={isActive}
                  className="h-8"
                >
                  <Link to={item.url} onClick={onNavigate}>
                    <HugeiconsIcon icon={item.icon} strokeWidth={2} />
                    <span>{item.title}</span>
                  </Link>
                </SidebarMenuSubButton>
              </SidebarMenuSubItem>
            )
          })}
        </SidebarMenuSub>
      ) : null}
    </SidebarMenuItem>
  )
}

export function AppSidebar({ ...props }: ComponentProps<typeof Sidebar>) {
  const location = useLocation()
  const { selectedWarehouse } = useWarehouse()
  const { currentUser, logout } = useAuth()
  const { isMobile, setOpen, setOpenMobile } = useSidebar()
  const isTabletOrSmaller = useIsTabletOrSmaller()
  const isSettingsActive = isActiveUrl(location.pathname, "/settings")
  const [settingsMenuOpen, setSettingsMenuOpen] = useState(isSettingsActive)
  const [writeOffsResetKey, setWriteOffsResetKey] = useState(0)
  const selectedWarehouseCode = selectedWarehouse?.code ?? "Склад"
  const visibleSettingsNavItems = settingsNavItems.filter((item) => {
    if (item.url === "/settings/warehouses") {
      return currentUser?.globalRole === "SYSTEM_ADMIN"
    }

    return (
      (item.url !== "/settings/users" && item.url !== "/settings/assets") ||
      (currentUser !== null && isGlobalAdministrator(currentUser.globalRole))
    )
  })

  function closeSidebarAfterNavigation() {
    if (!isTabletOrSmaller) {
      return
    }

    if (isMobile) {
      setOpenMobile(false)
      return
    }

    setOpen(false)
  }

  function handleNavigationClick() {
    setSettingsMenuOpen(false)
    closeSidebarAfterNavigation()
  }

  function handleSettingsNavigationClick() {
    closeSidebarAfterNavigation()
  }

  function toggleSettingsMenu() {
    const next = !settingsMenuOpen

    if (next) {
      setWriteOffsResetKey((current) => current + 1)
    }

    setSettingsMenuOpen(next)
  }

  return (
    <Sidebar {...props} collapsible="offcanvas">
      <SidebarHeader className="h-(--header-height) shrink-0 justify-center px-2 py-0">
        <SidebarMenu className="h-full">
          <SidebarMenuItem className="h-full">
            <SidebarMenuButton asChild className="h-full">
              <Link to="/" onClick={handleNavigationClick}>
                <span className="flex aspect-square size-8 shrink-0 items-center justify-center rounded-lg bg-sidebar-primary text-sidebar-primary-foreground">
                  <HugeiconsIcon icon={WarehouseIcon} strokeWidth={2} />
                </span>

                <span className="grid flex-1 text-left text-sm leading-tight">
                  <span className="truncate font-semibold">WMS Panel</span>
                  <span className="truncate text-xs">
                    {selectedWarehouseCode}
                  </span>
                </span>
              </Link>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarHeader>

      <SidebarContent>
        <WarehouseSelector />

        {navGroups.map((group) => (
          <SidebarGroup key={group.title}>
            <SidebarGroupLabel>{group.title}</SidebarGroupLabel>

            <SidebarGroupContent>
              <SidebarMenu>
                {group.items.map((item) => {
                  return (
                    <SidebarMenuItem key={item.title}>
                      <SidebarMenuButton
                        asChild
                        isActive={isActiveUrl(location.pathname, item.url)}
                        tooltip={item.title}
                        className="h-10 py-1"
                      >
                        <Link to={item.url} onClick={handleNavigationClick}>
                          <span className="flex size-8 shrink-0 items-center justify-center">
                            <HugeiconsIcon icon={item.icon} strokeWidth={2} />
                          </span>
                          <span>{item.title}</span>
                        </Link>
                      </SidebarMenuButton>
                    </SidebarMenuItem>
                  )
                })}

                {group.title === "Имущество" ? (
                  <WriteOffsSidebarMenu
                    key={`${location.pathname}:${writeOffsResetKey}`}
                    currentPath={location.pathname}
                    onOpen={() => setSettingsMenuOpen(false)}
                    onNavigate={closeSidebarAfterNavigation}
                  />
                ) : null}
              </SidebarMenu>
            </SidebarGroupContent>
          </SidebarGroup>
        ))}
      </SidebarContent>

      <SidebarFooter>
        <SidebarMenu>
          {settingsMenuOpen && (
            <SidebarMenuSub id="settings-submenu">
              {visibleSettingsNavItems.map((item) => {
                return (
                  <SidebarMenuSubItem key={item.title}>
                    <SidebarMenuSubButton
                      asChild
                      size="md"
                      isActive={isActiveUrl(location.pathname, item.url)}
                      className="h-8"
                    >
                      <Link
                        to={item.url}
                        onClick={handleSettingsNavigationClick}
                      >
                        <HugeiconsIcon icon={item.icon} strokeWidth={2} />
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
              isActive={settingsMenuOpen || isSettingsActive}
              aria-controls="settings-submenu"
              aria-expanded={settingsMenuOpen}
              onClick={toggleSettingsMenu}
              className="h-10 py-1"
            >
              <span className="flex size-8 shrink-0 items-center justify-center">
                <HugeiconsIcon icon={Settings02Icon} strokeWidth={2} />
              </span>
              <span>Настройки</span>
              <HugeiconsIcon
                icon={settingsMenuOpen ? ArrowDown01Icon : ArrowRight01Icon}
                strokeWidth={2}
                className="ml-auto transition-transform"
              />
            </SidebarMenuButton>
          </SidebarMenuItem>

          <SidebarMenuItem>
            <SidebarMenuButton
              type="button"
              tooltip="Выйти"
              className="h-10 py-1"
              onClick={() => void logout()}
            >
              <span className="flex size-8 shrink-0 items-center justify-center">
                <HugeiconsIcon icon={Logout03Icon} strokeWidth={2} />
              </span>
              <span className="truncate">
                {currentUser?.displayName || currentUser?.username || "Выйти"}
              </span>
              <span className="ml-auto text-xs text-muted-foreground">
                Выйти
              </span>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarFooter>
    </Sidebar>
  )
}
