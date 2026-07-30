import { SearchableMultiSelectFilter } from "@/components/searchable-multi-select-filter"
import { Button } from "@/components/ui/button"

import {
  EMPTY_ADMIN_USER_FILTERS,
  hasActiveAdminUserFilters,
  type AdminUserFilterOptions,
  type AdminUserFilters,
  type AdminUserMobileAppAccess,
  type AdminUserStatus,
} from "./user-filtering"

export function UserFilters({
  filters,
  options,
  onChange,
}: {
  filters: AdminUserFilters
  options: AdminUserFilterOptions
  onChange: (filters: AdminUserFilters) => void
}) {
  const hasActiveFilters = hasActiveAdminUserFilters(filters)

  return (
    <section
      aria-label="Фильтры пользователей"
      className="flex flex-col items-stretch gap-2 rounded-lg border bg-card p-2 sm:flex-row sm:flex-wrap sm:items-center"
    >
      <SearchableMultiSelectFilter
        label="Логин"
        options={options.logins}
        selected={filters.logins}
        onApply={(logins) => onChange({ ...filters, logins })}
      />
      <SearchableMultiSelectFilter
        label="Имя"
        options={options.names}
        selected={filters.names}
        onApply={(names) => onChange({ ...filters, names })}
      />
      <SearchableMultiSelectFilter
        label="Email"
        options={options.emails}
        selected={filters.emails}
        onApply={(emails) => onChange({ ...filters, emails })}
      />
      <SearchableMultiSelectFilter
        label="Роль"
        options={options.roles}
        selected={filters.roles}
        onApply={(roles) => onChange({ ...filters, roles })}
      />
      <SearchableMultiSelectFilter
        label="Статус"
        options={options.statuses}
        selected={filters.statuses}
        onApply={(statuses: AdminUserStatus[]) =>
          onChange({ ...filters, statuses })
        }
      />
      <SearchableMultiSelectFilter
        label="Приложение"
        options={options.mobileAppAccesses}
        selected={filters.mobileAppAccesses}
        onApply={(mobileAppAccesses: AdminUserMobileAppAccess[]) =>
          onChange({ ...filters, mobileAppAccesses })
        }
      />
      {hasActiveFilters ? (
        <Button
          type="button"
          size="default"
          variant="ghost"
          className="h-9 w-full self-start sm:w-auto"
          onClick={() => onChange(EMPTY_ADMIN_USER_FILTERS)}
        >
          Сбросить фильтры
        </Button>
      ) : null}
    </section>
  )
}
