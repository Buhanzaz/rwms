import {
  Box,
  CalendarClock,
  ClipboardList,
  CarFront,
  ChevronLeft,
  ChevronRight,
  CircleAlert,
  Settings2,
  SquareUserRound,
  Truck,
} from 'lucide-react';
import type { ReactNode } from 'react';
import type { RoutePlan, WarehouseWorkspace } from '../domain/types';
import { Button } from '../components/ui';
import { useUiStore, type LeftSection } from '../stores/ui-store';

const nav: Array<{ id: LeftSection; label: string; icon: ReactNode; count?: (workspace: WarehouseWorkspace, plan: RoutePlan | null) => number }> = [
  { id: 'WAREHOUSE', label: 'Склад', icon: <Box size={17} /> },
  { id: 'DRIVERS', label: 'Водители', icon: <SquareUserRound size={17} />, count: (workspace) => workspace.drivers.length },
  { id: 'VEHICLES', label: 'Машины', icon: <CarFront size={17} />, count: (workspace) => workspace.vehicles.length },
  { id: 'SHIFTS', label: 'Смены', icon: <CalendarClock size={17} />, count: (workspace) => workspace.shifts.length },
  { id: 'REQUESTS', label: 'Доставки', icon: <Truck size={17} />, count: (workspace) => workspace.requests.length },
  { id: 'PLAN_DAY', label: 'План дня', icon: <ClipboardList size={17} />, count: (_workspace, plan) => plan?.metrics.request_count ?? 0 },
  { id: 'UNASSIGNED', label: 'Нераспределённые', icon: <CircleAlert size={17} />, count: (_workspace, plan) => plan?.unassigned.length ?? 0 },
  { id: 'SETTINGS', label: 'Настройки', icon: <Settings2 size={17} /> },
];

export function Sidebar({ workspace, plan }: { workspace: WarehouseWorkspace; plan: RoutePlan | null }) {
  const section = useUiStore((state) => state.section);
  const setSection = useUiStore((state) => state.setSection);
  const collapsed = useUiStore((state) => state.sidebarsCollapsed);
  const toggle = useUiStore((state) => state.toggleSidebars);
  return (
    <aside className="sidebar" aria-label="Разделы логистического стенда">
      <header className="sidebar__head"><strong>Рабочая область</strong><Button variant="ghost" size="sm" onClick={toggle} aria-label={collapsed ? 'Развернуть панели' : 'Свернуть панели'}>{collapsed ? <ChevronRight size={16} /> : <ChevronLeft size={16} />}</Button></header>
      <nav className="sidebar__nav">
        {nav.map((item) => <button className="nav-item" aria-current={section === item.id ? 'page' : undefined} key={item.id} onClick={() => setSection(item.id)} title={item.label}>
          {item.icon}<span className="nav-item__label">{item.label}</span>{item.count ? <span className="nav-item__count">{item.count(workspace, plan)}</span> : null}
        </button>)}
      </nav>
    </aside>
  );
}
