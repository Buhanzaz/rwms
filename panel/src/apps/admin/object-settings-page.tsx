import {
  DeliveryTruck01Icon,
  TruckDeliveryIcon,
  UserGroupIcon,
  UserIcon,
  Wrench01Icon,
} from "@hugeicons/core-free-icons"
import { HugeiconsIcon } from "@hugeicons/react"
import { Navigate, useNavigate, useParams } from "react-router-dom"

import type { WarehouseInfo } from "@/api/warehouse-api"
import { AdminObjectScope } from "@/apps/admin/admin-object-scope"
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert"
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { useAuth } from "@/features/auth/use-auth"
import { AdminCatalogSettingsPage } from "@/features/settings/fleet/admin-catalog-settings-page"
import { LogisticsSettingsPage } from "@/features/settings/logistics"
import { RepairCapacitySettingsCard } from "@/features/settings/logistics/repair-capacity-settings-card"
import { TaskBoardSettingsPage } from "@/features/settings/task-board/task-board-settings-page"

const sections = [
  { value: "drivers", label: "Водители", icon: UserIcon },
  { value: "transport", label: "Транспорт", icon: DeliveryTruck01Icon },
  { value: "trailers", label: "Прицепы", icon: TruckDeliveryIcon },
  { value: "brigades", label: "Бригады", icon: UserGroupIcon },
  { value: "workers", label: "Рабочие", icon: UserIcon },
  { value: "repair-places", label: "Ремонтные места", icon: Wrench01Icon },
] as const

export type ObjectSettingsSection = (typeof sections)[number]["value"]

function isObjectSettingsSection(
  value: string | undefined
): value is ObjectSettingsSection {
  return sections.some((section) => section.value === value)
}

function RepairPlacesSettings({ warehouse }: { warehouse: WarehouseInfo }) {
  const { accessToken } = useAuth()

  if (!accessToken) {
    return (
      <Alert variant="destructive">
        <AlertTitle>Нет токена доступа</AlertTitle>
        <AlertDescription>
          Повторите вход, чтобы загрузить настройки ремонтных мест.
        </AlertDescription>
      </Alert>
    )
  }

  return (
    <RepairCapacitySettingsCard
      accessToken={accessToken}
      warehouseId={warehouse.id}
      warehouseName={warehouse.name}
    />
  )
}

function SectionContent({
  section,
  warehouse,
}: {
  section: ObjectSettingsSection
  warehouse: WarehouseInfo
}) {
  switch (section) {
    case "drivers":
      return <LogisticsSettingsPage section="drivers" />
    case "transport":
      return <AdminCatalogSettingsPage kind="vehicle" warehouse={warehouse} />
    case "trailers":
      return <AdminCatalogSettingsPage kind="trailer" warehouse={warehouse} />
    case "brigades":
      return <TaskBoardSettingsPage section="groups" />
    case "workers":
      return <TaskBoardSettingsPage section="workers" />
    case "repair-places":
      return <RepairPlacesSettings warehouse={warehouse} />
  }
}

export function ObjectSettingsPage() {
  const { section } = useParams<{ section?: string }>()
  const navigate = useNavigate()

  if (section === "maintenance") {
    return <Navigate to="/admin/estimates-repairs?catalog=maintenance-settings" replace />
  }
  if (section === undefined) {
    return <Navigate to="/admin/object-settings/drivers" replace />
  }
  if (!isObjectSettingsSection(section)) {
    return <Navigate to="/admin/object-settings/drivers" replace />
  }

  return (
    <AdminObjectScope inlineSelector>
      {(warehouse, objectSelector) => (
        <div className="relative flex min-h-0 flex-1 flex-col gap-4">
          <Tabs
            className="min-w-0"
            value={section}
            onValueChange={(value) =>
              navigate(`/admin/object-settings/${value}`)
            }
          >
            <div className="flex max-w-full items-center gap-2 overflow-x-auto pb-1">
              <div className="shrink-0">{objectSelector}</div>
              <TabsList className="min-w-max bg-muted/75 shadow-xs backdrop-blur-md">
                {sections.map((item) => (
                  <TabsTrigger key={item.value} value={item.value}>
                    <HugeiconsIcon
                      icon={item.icon}
                      data-icon="inline-start"
                      aria-hidden="true"
                    />
                    {item.label}
                  </TabsTrigger>
                ))}
              </TabsList>
            </div>
          </Tabs>
          <div className="min-h-0 flex-1">
            <SectionContent section={section} warehouse={warehouse} />
          </div>
        </div>
      )}
    </AdminObjectScope>
  )
}
