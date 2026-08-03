export type HeaderBreadcrumb = {
  title: string
  to?: string
}

type RentalItemHeaderBreadcrumb = {
  city: string
  number: string
}

const routeTitles = [
  { path: "/settings/kpi", title: "Настройка KPI" },
  { path: "/settings/estimates-repairs", title: "Настройка смет и ремонтов" },
  { path: "/settings/task-board", title: "Настройка доски задач" },
  { path: "/settings/logistics", title: "Настройки логистики" },
  { path: "/settings/rental", title: "Аренда и представления" },
  { path: "/assistant", title: "Чат" },
  { path: "/warehouse", title: "Склад" },
  { path: "/equipment", title: "Доп. оборудование" },
  { path: "/inventory", title: "Инвентаризация" },
  { path: "/logistics/order-tasks", title: "Задания" },
  { path: "/logistics/tasks", title: "Перемещение" },
  { path: "/logistics/returns", title: "Возврат из аренды" },
  { path: "/logistics/shipments", title: "Отгрузка в аренду" },
  { path: "/logistics/transfers", title: "Перемещения" },
  { path: "/estimates", title: "Сметы" },
  { path: "/repairs", title: "Ремонты" },
  { path: "/task-board", title: "Доска задач" },
  { path: "/acceptance", title: "Приёмка и доработки" },
  { path: "/write-offs", title: "Списание" },
  { path: "/settings", title: "Настройки" },
  { path: "/kpi", title: "KPI" },
] as const

function getEstimateCatalogBreadcrumbTitle(catalog: string | null) {
  switch (catalog) {
    case "repair-estimate-catalog-canvas":
      return "Конструктор каталога смет"
    case "repair-estimate-catalog-works":
      return "Работы"
    case "repair-estimate-catalog-materials":
      return "Материалы"
    case "repair-estimate-catalog-furniture":
      return "Мебель"
    default:
      return null
  }
}

export function resolveHeaderBreadcrumbs(
  pathname: string,
  search: string,
  rentalItemBreadcrumb: RentalItemHeaderBreadcrumb | null,
  repairCabinNumber: string | null,
  orderNumber: string | null = null
): HeaderBreadcrumb[] {
  const searchParams = new URLSearchParams(search)

  if (pathname.startsWith("/warehouse/")) {
    if (rentalItemBreadcrumb === null) {
      return [{ title: "Склад", to: "/warehouse" }]
    }

    return [
      { title: "Склад", to: "/warehouse" },
      { title: rentalItemBreadcrumb.city, to: "/warehouse" },
      { title: rentalItemBreadcrumb.number },
    ]
  }

  if (pathname === "/settings/estimates-repairs") {
    const catalogTitle = getEstimateCatalogBreadcrumbTitle(
      searchParams.get("catalog")
    )

    return [
      { title: "Настройки" },
      { title: "Настройка смет и ремонтов" },
      ...(catalogTitle ? [{ title: catalogTitle }] : []),
    ]
  }

  if (pathname === "/settings/kpi") {
    return [{ title: "Настройки" }, { title: "KPI" }]
  }

  if (pathname === "/logistics/returns") {
    return [{ title: "Возврат из аренды" }]
  }

  if (pathname === "/logistics/order-tasks") {
    return [{ title: "Задания" }]
  }

  if (pathname === "/logistics/shipments") {
    return [{ title: "Отгрузка в аренду" }]
  }

  if (pathname === "/logistics/transfers") {
    return [{ title: "Перемещения" }]
  }

  if (pathname === "/settings/warehouses") {
    return [{ title: "Настройки" }, { title: "Склады" }]
  }

  if (pathname === "/settings/users") {
    return [{ title: "Настройки" }, { title: "Пользователи" }]
  }

  if (pathname === "/settings/task-board") {
    return [{ title: "Настройки" }, { title: "Настройка доски задач" }]
  }

  if (pathname === "/settings/rental") {
    return [{ title: "Настройки" }, { title: "Аренда и представления" }]
  }

  const inventoryHistoryMatch = /^\/inventory\/history\/([^/]+)$/.exec(pathname)
  if (inventoryHistoryMatch) {
    return [
      { title: "Инвентаризация" },
      { title: "История", to: "/inventory/history" },
      { title: "Результат" },
    ]
  }

  if (pathname === "/inventory/history") {
    return [{ title: "Инвентаризация" }, { title: "История" }]
  }

  const inventoryFinishMatch = /^\/inventory\/([^/]+)\/finish$/.exec(pathname)
  if (inventoryFinishMatch) {
    return [
      { title: "Инвентаризация" },
      { title: "Сессия", to: `/inventory/${inventoryFinishMatch[1]}` },
      { title: "Сверка" },
    ]
  }

  if (/^\/inventory\/[^/]+$/.test(pathname)) {
    return [{ title: "Инвентаризация" }, { title: "Сессия" }]
  }

  if (pathname === "/acceptance" && searchParams.has("acceptanceId")) {
    return [
      { title: "Приёмка и доработки", to: "/acceptance" },
      { title: repairCabinNumber ?? "Бытовка" },
    ]
  }

  if (pathname === "/write-offs" && searchParams.has("writeOffId")) {
    return [
      { title: "Списание" },
      { title: "Склад", to: "/write-offs" },
      { title: repairCabinNumber ?? "Бытовка" },
    ]
  }

  if (pathname === "/write-offs") {
    return [{ title: "Списание" }, { title: "Склад" }]
  }

  if (pathname === "/write-offs/equipment") {
    return [{ title: "Списание" }, { title: "Доп. оборудование" }]
  }

  if (pathname === "/estimates" && searchParams.has("estimateId")) {
    return [{ title: "Сметы", to: "/estimates" }, { title: "Смета" }]
  }

  if (pathname === "/estimates" && searchParams.get("create") === "1") {
    return [{ title: "Сметы", to: "/estimates" }, { title: "Новая смета" }]
  }

  if (pathname === "/repairs" && searchParams.has("repairId")) {
    return [{ title: "Ремонты", to: "/repairs" }, { title: "Задание" }]
  }

  if (pathname === "/repairs" && searchParams.get("create") === "1") {
    return [{ title: "Ремонты", to: "/repairs" }, { title: "Новое задание" }]
  }

  if (/^\/orders\/[0-9a-f-]{36}$/i.test(pathname)) {
    return [
      { title: "Бронирование", to: "/orders" },
      { title: orderNumber ?? "Бронирование" },
    ]
  }

  if (pathname.startsWith("/orders")) {
    return [{ title: "Бронирование", to: "/orders" }]
  }

  if (pathname === "/") {
    return [{ title: "Главная" }]
  }

  return [
    {
      title:
        routeTitles.find((route) => pathname === route.path)?.title ??
        "WMS Panel",
    },
  ]
}
