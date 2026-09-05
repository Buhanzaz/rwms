import {
  Box,
  CalendarClock,
  ClipboardList,
  CarFront,
  CircleAlert,
  ContactRound,
  SquareUserRound,
  Truck,
} from 'lucide-react';
import type { ReactNode } from 'react';
import type { RoutePlan, WarehouseWorkspace } from '../domain/types';
import { useUiStore, type LeftSection } from '../stores/ui-store';
import logotypeUrl from '../assets/logotype.svg';

const nav: Array<{ id: LeftSection; label: string; icon: ReactNode; count?: (workspace: WarehouseWorkspace, plan: RoutePlan | null) => number }> = [
  { id: 'WAREHOUSE', label: 'Склад', icon: <Box size={17} /> },
  { id: 'DRIVERS', label: 'Водители', icon: <SquareUserRound size={17} />, count: (workspace) => workspace.drivers.length },
  { id: 'CONTRACTORS', label: 'Наёмные водители', icon: <ContactRound size={17} /> },
  { id: 'VEHICLES', label: 'Транспорт', icon: <CarFront size={17} />, count: (workspace) => workspace.vehicles.length },
  { id: 'SHIFTS', label: 'Смены', icon: <CalendarClock size={17} />, count: (workspace) => workspace.shifts.length },
  { id: 'REQUESTS', label: 'Доставки', icon: <Truck size={17} />, count: (workspace) => workspace.requests.length },
  { id: 'PLAN_DAY', label: 'План дня', icon: <ClipboardList size={17} />, count: (_workspace, plan) => plan?.metrics.request_count ?? 0 },
  { id: 'UNASSIGNED', label: 'Нераспределённые', icon: <CircleAlert size={17} />, count: (_workspace, plan) => plan?.unassigned.length ?? 0 },
];

export function Sidebar({ workspace, plan, pendingActionCount = 0, onNavigate, onOpenWarehouse }: {
  workspace: WarehouseWorkspace;
  plan: RoutePlan | null;
  pendingActionCount?: number;
  onNavigate?: () => void;
  onOpenWarehouse?: () => void;
}) {
  const section = useUiStore((state) => state.section);
  const setSection = useUiStore((state) => state.setSection);
  const setMapTool = useUiStore((state) => state.setMapTool);
  return (
    <aside className="sidebar" aria-label="Разделы логистики">
      <button type="button" className="sidebar__brand" aria-label="Blockbox — Логистика: открыть склад" onClick={() => {
        setSection('WAREHOUSE');
        onOpenWarehouse?.();
        onNavigate?.();
      }}>
        <img src={logotypeUrl} width={32} height={32} alt="" />
        <span className="sidebar__brand-copy"><strong translate="no">BLOCKBOX</strong><span>Логистика</span></span>
      </button>
      <nav className="sidebar__nav">
        {nav.map((item) => {
          const count = item.id === 'PLAN_DAY' && pendingActionCount > 0
            ? pendingActionCount
            : item.count?.(workspace, plan);
          return <button className="nav-item" aria-label={item.label} aria-current={section === item.id ? 'page' : undefined} key={item.id} onClick={() => { setSection(item.id); if (item.id === 'REQUESTS') setMapTool('ADD_DELIVERY'); onNavigate?.(); }} title={item.label}>
            {item.icon}<span className="nav-item__label">{item.label}</span>{count !== undefined ? <span className="nav-item__count">{count}</span> : null}
          </button>;
        })}
      </nav>
    </aside>
  );
}
