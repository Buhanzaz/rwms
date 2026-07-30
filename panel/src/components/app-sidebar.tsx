import {
  Fragment,
  useEffect,
  useState,
  type ComponentProps,
  type ReactNode,
} from "react"
import { Link, useLocation } from "react-router-dom"
import { useQuery, useQueryClient } from "@tanstack/react-query"
import { HugeiconsIcon, type IconSvgElement } from "@hugeicons/react"
import {
  ArrowDown01Icon,
  ArrowRight01Icon,
  Add01Icon,
  AiChat02Icon,
  ChartIncreaseIcon,
  CheckmarkCircle02Icon,
  Clock01Icon,
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
  Loading03Icon,
  Logout03Icon,
  PackageSearchIcon,
  Settings02Icon,
  UserGroupIcon,
  WarehouseIcon,
  WasteIcon,
  Wrench01Icon,
} from "@hugeicons/core-free-icons"
import { useWarehouse } from "@/hooks/use-warehouse"
import { isGlobalAdministrator } from "@/features/auth/auth-model"
import { useAuth } from "@/features/auth/use-auth"
import { ORDERS_NAVIGATION } from "@/features/orders/permissions/orders-permissions"
import {
  INVENTORY_QUERY_KEY,
  getActiveInventory,
  inventoryActiveQueryKey,
  subscribeInventory,
} from "@/features/inventory/api/inventory-api"
import { getInventoryActor } from "@/features/inventory/inventory-access"
import type { InventoryActorSnapshot } from "@/features/inventory/model/inventory"
import { cn } from "@/lib/utils"

import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { Button } from "@/components/ui/button"
import { Popover, PopoverAnchor, PopoverContent } from "@/components/ui/popover"

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
  SidebarSeparator,
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
    title: "Аренда и представления",
    url: "/settings/rental",
    icon: Clock01Icon,
  },
  {
    title: "Склады",
    url: "/settings/warehouses",
    icon: WarehouseIcon,
  },
  {
    title: "Настройки бытовок",
    url: "/settings/cabins",
    icon: Settings02Icon,
  },
  {
    title: "KPI",
    url: "/settings/kpi",
    icon: ChartIncreaseIcon,
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

function RwmsLogoMark() {
  return (
    <span
      data-testid="rwms-logo-mark"
      aria-hidden="true"
      className="flex aspect-square size-8 shrink-0 items-center justify-center rounded-lg bg-[#1775a7] text-white group-data-[collapsible=icon]:size-9"
    >
      <svg
        viewBox="0 0 32 32"
        fill="none"
        className="size-5"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        <path d="M5.5 8.5 16 3l10.5 5.5v15L16 29 5.5 23.5v-15Z" />
        <path d="m5.5 8.5 10.5 5.75 10.5-5.75M16 14.25V29" />
      </svg>
    </span>
  )
}

function SidebarInlineNavItem({
  item,
  isActive,
  onNavigate,
}: {
  item: SidebarNavItem
  isActive: boolean
  onNavigate: () => void
}) {
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
}

function SidebarFlyoutNavItem({
  item,
  isActive,
  onNavigate,
}: {
  item: SidebarNavItem
  isActive: boolean
  onNavigate: () => void
}) {
  return (
    <li key={item.title}>
      <Button
        asChild
        variant="ghost"
        data-active={isActive || undefined}
        className="h-9 w-full justify-start rounded-md px-2 text-sidebar-foreground hover:bg-sidebar-accent hover:text-sidebar-accent-foreground data-[active=true]:bg-sidebar-accent data-[active=true]:font-medium data-[active=true]:text-sidebar-accent-foreground"
      >
        <Link to={item.url} onClick={onNavigate}>
          <HugeiconsIcon icon={item.icon} strokeWidth={2} />
          <span>{item.title}</span>
        </Link>
      </Button>
    </li>
  )
}

function SidebarCollapsedFlyout({
  id,
  title,
  open,
  onOpenChange,
  align = "start",
  contentClassName,
  trigger,
  children,
}: {
  id: string
  title: string
  open: boolean
  onOpenChange: (open: boolean) => void
  align?: "start" | "center" | "end"
  contentClassName?: string
  trigger: ReactNode
  children: ReactNode
}) {
  return (
    <Popover open={open} onOpenChange={onOpenChange}>
      <PopoverAnchor asChild>
        <div>{trigger}</div>
      </PopoverAnchor>
      <PopoverContent
        id={id}
        side="right"
        align={align}
        sideOffset={8}
        aria-label={`Меню «${title}»`}
        className={cn(
          "w-64 gap-1 border-0 bg-sidebar p-2 text-sidebar-foreground",
          contentClassName
        )}
      >
        <p className="px-2 py-1 text-xs font-medium text-sidebar-foreground/70">
          {title}
        </p>
        <ul className="flex flex-col gap-1">{children}</ul>
      </PopoverContent>
    </Popover>
  )
}

function SidebarCollapsedExpandIndicator() {
  return (
    <span
      aria-hidden="true"
      data-slot="sidebar-collapsed-expand-indicator"
      className="pointer-events-none absolute top-1/2 -right-1.5 z-10 hidden size-[18px] -translate-y-1/2 text-sidebar-foreground/70 group-data-[collapsible=icon]:flex"
    >
      <HugeiconsIcon
        icon={ArrowRight01Icon}
        strokeWidth={2}
        className="block size-[18px]"
      />
    </span>
  )
}

function InventorySidebarMenu({
  currentPath,
  warehouseId,
  actor,
  collapsed,
  onOpen,
  onNavigate,
}: {
  currentPath: string
  warehouseId: string | null
  actor: InventoryActorSnapshot | null
  collapsed: boolean
  onOpen: () => void
  onNavigate: () => void
}) {
  const queryClient = useQueryClient()
  const isInventoryActive = isActiveUrl(currentPath, "/inventory")
  const [menuOpenState, setMenuOpenState] = useState(() => ({
    collapsed,
    open: isInventoryActive && !collapsed,
  }))
  const menuOpen = menuOpenState.collapsed === collapsed && menuOpenState.open
  const activeQuery = useQuery({
    queryKey: inventoryActiveQueryKey(warehouseId ?? "none"),
    queryFn: () =>
      warehouseId && actor
        ? getActiveInventory({ warehouseId, actor })
        : Promise.resolve(null),
    enabled: warehouseId !== null && actor !== null,
  })
  const isLoadingActiveInventory =
    activeQuery.isPending && activeQuery.fetchStatus === "fetching"

  useEffect(
    () =>
      subscribeInventory(() => {
        void queryClient.invalidateQueries({ queryKey: INVENTORY_QUERY_KEY })
      }),
    [queryClient]
  )

  const items: SidebarNavItem[] = activeQuery.data
    ? [
        {
          title: "Продолжить инвентаризацию",
          url: `/inventory/${activeQuery.data.id}`,
          icon: ClipboardCheckIcon,
        },
        {
          title: "Закончить инвентаризацию",
          url: `/inventory/${activeQuery.data.id}/finish`,
          icon: CheckmarkCircle02Icon,
        },
        {
          title: "История инвентаризаций",
          url: "/inventory/history",
          icon: ClipboardListIcon,
        },
      ]
    : [
        {
          title: "Создать инвентаризацию",
          url: "/inventory",
          icon: Add01Icon,
        },
        {
          title: "История инвентаризаций",
          url: "/inventory/history",
          icon: ClipboardListIcon,
        },
      ]

  function setMenuVisibility(next: boolean) {
    if (next) {
      onOpen()
    }

    setMenuOpenState({ collapsed, open: next })
  }

  function handleNavigation() {
    setMenuOpenState({ collapsed, open: false })
    onNavigate()
  }

  function renderMenuItems(flyout: boolean) {
    const renderItem = (item: SidebarNavItem) => {
      const isActive = currentPath === item.url

      return flyout ? (
        <SidebarFlyoutNavItem
          key={item.title}
          item={item}
          isActive={isActive}
          onNavigate={handleNavigation}
        />
      ) : (
        <SidebarInlineNavItem
          key={item.title}
          item={item}
          isActive={isActive}
          onNavigate={handleNavigation}
        />
      )
    }

    return (
      <>
        {isLoadingActiveInventory ? (
          flyout ? (
            <li className="flex h-9 items-center gap-2 px-2 text-sm text-sidebar-foreground/70">
              <HugeiconsIcon
                icon={Loading03Icon}
                strokeWidth={2}
                className="animate-spin"
              />
              <span>Проверяем сессию…</span>
            </li>
          ) : (
            <SidebarMenuSubItem>
              <SidebarMenuSubButton
                aria-disabled="true"
                size="md"
                className="h-8"
              >
                <HugeiconsIcon
                  icon={Loading03Icon}
                  strokeWidth={2}
                  className="animate-spin"
                />
                <span>Проверяем сессию…</span>
              </SidebarMenuSubButton>
            </SidebarMenuSubItem>
          )
        ) : (
          items.slice(0, -1).map(renderItem)
        )}
        {items.slice(-1).map(renderItem)}
      </>
    )
  }

  const menuButton = (
    <SidebarMenuButton
      type="button"
      isActive={menuOpen || isInventoryActive}
      aria-controls={
        collapsed ? "inventory-submenu-flyout" : "inventory-submenu"
      }
      aria-expanded={menuOpen}
      aria-haspopup={collapsed ? "menu" : undefined}
      onClick={() => setMenuVisibility(!menuOpen)}
      tooltip="Инвентаризация"
      className="h-10 py-1 group-data-[collapsible=icon]:w-[42px]!"
    >
      <span className="flex size-8 shrink-0 items-center justify-center group-data-[collapsible=icon]:size-9">
        <HugeiconsIcon icon={ClipboardCheckIcon} strokeWidth={2} />
      </span>
      <span>Инвентаризация</span>
      <HugeiconsIcon
        icon={menuOpen ? ArrowDown01Icon : ArrowRight01Icon}
        strokeWidth={2}
        className="ml-auto transition-transform group-data-[collapsible=icon]:hidden"
      />
    </SidebarMenuButton>
  )

  return (
    <SidebarMenuItem>
      {collapsed ? (
        <>
          <SidebarCollapsedFlyout
            id="inventory-submenu-flyout"
            title="Инвентаризация"
            open={menuOpen}
            onOpenChange={setMenuVisibility}
            contentClassName="w-72"
            trigger={menuButton}
          >
            {renderMenuItems(true)}
          </SidebarCollapsedFlyout>
          <SidebarCollapsedExpandIndicator />
        </>
      ) : (
        <>
          {menuButton}
          {menuOpen ? (
            <SidebarMenuSub id="inventory-submenu">
              {renderMenuItems(false)}
            </SidebarMenuSub>
          ) : null}
        </>
      )}
    </SidebarMenuItem>
  )
}

function WarehouseSelector({ collapsed }: { collapsed: boolean }) {
  const { warehouses, selectedWarehouse, setSelectedWarehouseId } =
    useWarehouse()

  if (collapsed) {
    return (
      <SidebarGroup className="pt-3 pb-0">
        <SidebarGroupContent>
          <CollapsedWarehouseSelector />
        </SidebarGroupContent>
      </SidebarGroup>
    )
  }

  return (
    <SidebarGroup className="pt-3 pb-0">
      <SidebarGroupContent>
        <Select
          value={selectedWarehouse?.id ?? ""}
          onValueChange={setSelectedWarehouseId}
        >
          <SelectTrigger aria-label="Выбор склада" className="w-full">
            <SelectValue placeholder="Выберите склад" />
          </SelectTrigger>

          <SelectContent position="popper">
            <SelectGroup>
              {warehouses.map((warehouse) => (
                <SelectItem key={warehouse.id} value={warehouse.id}>
                  {warehouse.name}
                </SelectItem>
              ))}
            </SelectGroup>
          </SelectContent>
        </Select>
      </SidebarGroupContent>
    </SidebarGroup>
  )
}

function CollapsedWarehouseSelector() {
  const { warehouses, selectedWarehouse, setSelectedWarehouseId } =
    useWarehouse()
  const selectedName = selectedWarehouse?.name ?? "—"

  return (
    <div
      data-slot="sidebar-collapsed-warehouse-selector"
      className="ml-0 flex h-9 w-9 shrink-0"
    >
      <Select
        value={selectedWarehouse?.id ?? ""}
        onValueChange={setSelectedWarehouseId}
      >
        <SelectTrigger
          aria-label={`Выбор склада: ${selectedName}`}
          className="size-full! justify-center gap-0 border-0 bg-transparent p-0! text-xs font-semibold shadow-none hover:bg-sidebar-accent focus-visible:ring-2 [&_[data-slot=select-value]]:w-full [&_[data-slot=select-value]]:justify-center [&>svg]:hidden"
        >
          <SelectValue placeholder="—" />
        </SelectTrigger>

        <SelectContent
          position="popper"
          side="right"
          align="start"
          sideOffset={8}
          className="min-w-20"
        >
          <SelectGroup>
            {warehouses.map((warehouse) => (
              <SelectItem key={warehouse.id} value={warehouse.id}>
                {warehouse.name}
              </SelectItem>
            ))}
          </SelectGroup>
        </SelectContent>
      </Select>
    </div>
  )
}

function WriteOffsSidebarMenu({
  currentPath,
  collapsed,
  onOpen,
  onNavigate,
}: {
  currentPath: string
  collapsed: boolean
  onOpen: () => void
  onNavigate: () => void
}) {
  const isWriteOffsActive = isActiveUrl(currentPath, "/write-offs")
  const [menuOpenState, setMenuOpenState] = useState(() => ({
    collapsed,
    open: isWriteOffsActive && !collapsed,
  }))
  const menuOpen = menuOpenState.collapsed === collapsed && menuOpenState.open

  function setMenuVisibility(next: boolean) {
    if (next) {
      onOpen()
    }

    setMenuOpenState({ collapsed, open: next })
  }

  function handleNavigation() {
    setMenuOpenState({ collapsed, open: false })
    onNavigate()
  }

  function toggleMenu() {
    setMenuVisibility(!menuOpen)
  }

  function renderMenuItems(flyout: boolean) {
    return writeOffNavItems.map((item) => {
      const isActive =
        item.url === "/write-offs"
          ? currentPath === item.url
          : currentPath.startsWith(item.url)

      return flyout ? (
        <SidebarFlyoutNavItem
          key={item.title}
          item={item}
          isActive={isActive}
          onNavigate={handleNavigation}
        />
      ) : (
        <SidebarInlineNavItem
          key={item.title}
          item={item}
          isActive={isActive}
          onNavigate={handleNavigation}
        />
      )
    })
  }

  const menuButton = (
    <SidebarMenuButton
      type="button"
      isActive={menuOpen || isWriteOffsActive}
      aria-controls={
        collapsed ? "write-offs-submenu-flyout" : "write-offs-submenu"
      }
      aria-expanded={menuOpen}
      aria-haspopup={collapsed ? "menu" : undefined}
      onClick={toggleMenu}
      tooltip="Списание"
      className="h-10 py-1 group-data-[collapsible=icon]:w-[42px]!"
    >
      <span className="flex size-8 shrink-0 items-center justify-center group-data-[collapsible=icon]:size-9">
        <HugeiconsIcon icon={WasteIcon} strokeWidth={2} />
      </span>
      <span>Списание</span>
      <HugeiconsIcon
        icon={menuOpen ? ArrowDown01Icon : ArrowRight01Icon}
        strokeWidth={2}
        className="ml-auto transition-transform group-data-[collapsible=icon]:hidden"
      />
    </SidebarMenuButton>
  )

  return (
    <SidebarMenuItem>
      {collapsed ? (
        <>
          <SidebarCollapsedFlyout
            id="write-offs-submenu-flyout"
            title="Списание"
            open={menuOpen}
            onOpenChange={setMenuVisibility}
            trigger={menuButton}
          >
            {renderMenuItems(true)}
          </SidebarCollapsedFlyout>
          <SidebarCollapsedExpandIndicator />
        </>
      ) : (
        <>
          {menuButton}
          {menuOpen ? (
            <SidebarMenuSub id="write-offs-submenu">
              {renderMenuItems(false)}
            </SidebarMenuSub>
          ) : null}
        </>
      )}
    </SidebarMenuItem>
  )
}

function OrdersSidebarMenu({
  currentPath,
  onNavigate,
}: {
  currentPath: string
  onNavigate: () => void
}) {
  return (
    <SidebarMenuItem>
      <SidebarMenuButton
        asChild
        isActive={isActiveUrl(currentPath, ORDERS_NAVIGATION.listPath)}
        tooltip={ORDERS_NAVIGATION.rootLabel}
        className="h-10 py-1"
      >
        <Link to={ORDERS_NAVIGATION.listPath} onClick={onNavigate}>
          <span className="flex size-8 shrink-0 items-center justify-center group-data-[collapsible=icon]:size-9">
            <HugeiconsIcon icon={ClipboardListIcon} strokeWidth={2} />
          </span>
          <span>{ORDERS_NAVIGATION.rootLabel}</span>
        </Link>
      </SidebarMenuButton>
    </SidebarMenuItem>
  )
}

function AssistantSidebarMenu({
  currentPath,
  onNavigate,
}: {
  currentPath: string
  onNavigate: () => void
}) {
  return (
    <SidebarMenuItem>
      <SidebarMenuButton
        asChild
        isActive={isActiveUrl(currentPath, "/assistant")}
        tooltip="Чат"
        className="h-10 py-1"
      >
        <Link to="/assistant" onClick={onNavigate}>
          <span className="flex size-8 shrink-0 items-center justify-center group-data-[collapsible=icon]:size-9">
            <HugeiconsIcon icon={AiChat02Icon} strokeWidth={2} />
          </span>
          <span>Чат</span>
        </Link>
      </SidebarMenuButton>
    </SidebarMenuItem>
  )
}

export function AppSidebar({ ...props }: ComponentProps<typeof Sidebar>) {
  const location = useLocation()
  const { selectedWarehouse } = useWarehouse()
  const { currentUser, logout } = useAuth()
  const { isMobile, setOpenMobile, state } = useSidebar()
  const sidebarCollapsed = !isMobile && state === "collapsed"
  const isSettingsActive = isActiveUrl(location.pathname, "/settings")
  const [settingsMenuOpenState, setSettingsMenuOpenState] = useState(() => ({
    sidebarCollapsed,
    open: isSettingsActive && !sidebarCollapsed,
  }))
  const settingsMenuOpen =
    settingsMenuOpenState.sidebarCollapsed === sidebarCollapsed &&
    settingsMenuOpenState.open
  const [writeOffsResetKey, setWriteOffsResetKey] = useState(0)
  const [inventoryResetKey, setInventoryResetKey] = useState(0)
  const visibleSettingsNavItems = settingsNavItems.filter((item) => {
    if (item.url === "/settings/warehouses") {
      return currentUser?.globalRole === "SYSTEM_ADMIN"
    }

    if (item.url === "/settings/rental") {
      return (
        currentUser !== null && isGlobalAdministrator(currentUser.globalRole)
      )
    }

    return (
      item.url !== "/settings/users" ||
      (currentUser !== null && isGlobalAdministrator(currentUser.globalRole))
    )
  })

  function closeSidebarAfterNavigation() {
    if (isMobile) {
      setOpenMobile(false)
    }
  }

  function handleNavigationClick() {
    setSettingsMenuOpenState({ sidebarCollapsed, open: false })
    setWriteOffsResetKey((current) => current + 1)
    setInventoryResetKey((current) => current + 1)
    closeSidebarAfterNavigation()
  }

  function handleSettingsNavigationClick() {
    setSettingsMenuOpenState({ sidebarCollapsed, open: false })
    closeSidebarAfterNavigation()
  }

  function setSettingsMenuVisibility(next: boolean) {
    if (next) {
      setWriteOffsResetKey((current) => current + 1)
      setInventoryResetKey((current) => current + 1)
    }

    setSettingsMenuOpenState({ sidebarCollapsed, open: next })
  }

  function toggleSettingsMenu() {
    setSettingsMenuVisibility(!settingsMenuOpen)
  }

  function renderSettingsMenuItems(flyout: boolean) {
    return visibleSettingsNavItems.map((item) => {
      const isActive = isActiveUrl(location.pathname, item.url)

      return flyout ? (
        <SidebarFlyoutNavItem
          key={item.title}
          item={item}
          isActive={isActive}
          onNavigate={handleSettingsNavigationClick}
        />
      ) : (
        <SidebarInlineNavItem
          key={item.title}
          item={item}
          isActive={isActive}
          onNavigate={handleSettingsNavigationClick}
        />
      )
    })
  }

  const settingsMenuButton = (
    <SidebarMenuButton
      type="button"
      isActive={settingsMenuOpen || isSettingsActive}
      aria-controls={
        sidebarCollapsed ? "settings-submenu-flyout" : "settings-submenu"
      }
      aria-expanded={settingsMenuOpen}
      aria-haspopup={sidebarCollapsed ? "menu" : undefined}
      onClick={toggleSettingsMenu}
      tooltip="Настройки"
      className="h-10 py-1 group-data-[collapsible=icon]:w-[42px]!"
    >
      <span className="flex size-8 shrink-0 items-center justify-center group-data-[collapsible=icon]:size-9">
        <HugeiconsIcon icon={Settings02Icon} strokeWidth={2} />
      </span>
      <span>Настройки</span>
      <HugeiconsIcon
        icon={settingsMenuOpen ? ArrowDown01Icon : ArrowRight01Icon}
        strokeWidth={2}
        className="ml-auto transition-transform group-data-[collapsible=icon]:hidden"
      />
    </SidebarMenuButton>
  )

  return (
    <Sidebar {...props} collapsible="icon">
      <SidebarHeader className="h-(--header-height) shrink-0 justify-center px-2 py-0">
        <SidebarMenu className="h-full">
          <SidebarMenuItem className="flex h-full items-center">
            <SidebarMenuButton asChild className="h-full">
              <Link to="/" onClick={handleNavigationClick}>
                <RwmsLogoMark />

                <span className="grid flex-1 text-left text-sm leading-tight">
                  <span className="truncate text-[15px] font-bold tracking-[-0.01em]">
                    RWMS panel
                  </span>
                </span>
              </Link>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>
      </SidebarHeader>

      <SidebarContent className="gap-1">
        <WarehouseSelector collapsed={sidebarCollapsed} />

        <SidebarGroup className="mt-2 py-0">
          <SidebarGroupLabel>Аренда</SidebarGroupLabel>
          <SidebarGroupContent>
            <SidebarMenu>
              <AssistantSidebarMenu
                currentPath={location.pathname}
                onNavigate={handleNavigationClick}
              />
              <OrdersSidebarMenu
                currentPath={location.pathname}
                onNavigate={handleNavigationClick}
              />
            </SidebarMenu>
          </SidebarGroupContent>
        </SidebarGroup>

        <SidebarSeparator className="mr-0! ml-[18px]! hidden w-4! group-data-[collapsible=icon]:block" />

        {navGroups.map((group, index) => (
          <Fragment key={group.title}>
            <SidebarGroup>
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
                            <span className="flex size-8 shrink-0 items-center justify-center group-data-[collapsible=icon]:size-9">
                              <HugeiconsIcon icon={item.icon} strokeWidth={2} />
                            </span>
                            <span>{item.title}</span>
                          </Link>
                        </SidebarMenuButton>
                      </SidebarMenuItem>
                    )
                  })}

                  {group.title === "Имущество" ? (
                    <>
                      <InventorySidebarMenu
                        key={`${selectedWarehouse?.id}:${location.pathname}:${inventoryResetKey}`}
                        currentPath={location.pathname}
                        warehouseId={selectedWarehouse?.id ?? null}
                        actor={
                          selectedWarehouse
                            ? getInventoryActor(
                                currentUser,
                                selectedWarehouse.id
                              )
                            : null
                        }
                        collapsed={sidebarCollapsed}
                        onOpen={() => {
                          setSettingsMenuOpenState({
                            sidebarCollapsed,
                            open: false,
                          })
                          setWriteOffsResetKey((current) => current + 1)
                        }}
                        onNavigate={closeSidebarAfterNavigation}
                      />
                      <WriteOffsSidebarMenu
                        key={`${location.pathname}:${writeOffsResetKey}`}
                        currentPath={location.pathname}
                        collapsed={sidebarCollapsed}
                        onOpen={() => {
                          setSettingsMenuOpenState({
                            sidebarCollapsed,
                            open: false,
                          })
                          setInventoryResetKey((current) => current + 1)
                        }}
                        onNavigate={closeSidebarAfterNavigation}
                      />
                    </>
                  ) : null}
                </SidebarMenu>
              </SidebarGroupContent>
            </SidebarGroup>

            {index < navGroups.length - 1 ? (
              <SidebarSeparator className="mr-0! ml-[18px]! hidden w-4! group-data-[collapsible=icon]:block" />
            ) : null}
          </Fragment>
        ))}
      </SidebarContent>

      <SidebarFooter>
        <SidebarMenu>
          {sidebarCollapsed ? (
            <SidebarMenuItem>
              <>
                <SidebarCollapsedFlyout
                  id="settings-submenu-flyout"
                  title="Настройки"
                  open={settingsMenuOpen}
                  onOpenChange={setSettingsMenuVisibility}
                  align="end"
                  trigger={settingsMenuButton}
                >
                  {renderSettingsMenuItems(true)}
                </SidebarCollapsedFlyout>
                <SidebarCollapsedExpandIndicator />
              </>
            </SidebarMenuItem>
          ) : (
            <>
              {settingsMenuOpen ? (
                <SidebarMenuSub id="settings-submenu">
                  {renderSettingsMenuItems(false)}
                </SidebarMenuSub>
              ) : null}
              <SidebarMenuItem>{settingsMenuButton}</SidebarMenuItem>
            </>
          )}

          <SidebarMenuItem>
            <SidebarMenuButton
              type="button"
              tooltip="Выйти"
              className="h-10 py-1"
              onClick={() => void logout()}
            >
              <span className="flex size-8 shrink-0 items-center justify-center group-data-[collapsible=icon]:size-9">
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
