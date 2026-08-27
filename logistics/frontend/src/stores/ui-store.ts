import { create } from 'zustand';
import type { MapClickDraft, MapSelection, OptimizationTraceEvent, SimulationOverride, UUID } from '../domain/types';

export type AppMode = 'EDITOR' | 'PLAN' | 'SIMULATION';
export type LeftSection =
  | 'SCENARIO'
  | 'WAREHOUSE'
  | 'ZONES'
  | 'ZONE_RELATIONS'
  | 'DRIVERS'
  | 'VEHICLES'
  | 'SHIFTS'
  | 'REQUESTS'
  | 'PLAN_DAY'
  | 'ROUTES'
  | 'UNASSIGNED'
  | 'SETTINGS';
export type MapTool = 'SELECT' | 'PLACE_WAREHOUSE' | 'ADD_DELIVERY' | 'ADD_PICKUP' | 'DRAW_ZONE' | 'CUT_ZONE' | 'EDIT_ZONE' | 'RELATE_ZONES';

export interface LayerVisibility {
  base: boolean;
  zones: boolean;
  zoneBorders: boolean;
  warehouse: boolean;
  deliveries: boolean;
  pickups: boolean;
  unassigned: boolean;
  candidates: boolean;
  routes: boolean;
  traveled: boolean;
  activeLeg: boolean;
  trucks: boolean;
  corridor: boolean;
  selected: boolean;
  truckRestrictions: boolean;
}

export interface ToastMessage {
  id: string;
  tone: 'success' | 'warning' | 'error' | 'info';
  title: string;
  detail?: string;
}

interface UiState {
  mode: AppMode;
  section: LeftSection;
  mapTool: MapTool;
  selected: MapSelection;
  mapClickDraft: MapClickDraft | null;
  relationSourceZoneId: UUID | null;
  layers: LayerVisibility;
  sidebarsCollapsed: boolean;
  toasts: ToastMessage[];
  simulationTimestamp: number | null;
  simulationPlaying: boolean;
  simulationSpeed: 1 | 5 | 10 | 20 | 60;
  simulationOverrides: SimulationOverride[];
  traceEvents: OptimizationTraceEvent[];
  setMode: (mode: AppMode) => void;
  setSection: (section: LeftSection) => void;
  setMapTool: (tool: MapTool) => void;
  setSelected: (selected: MapSelection) => void;
  setMapClickDraft: (draft: MapClickDraft | null) => void;
  setRelationSourceZoneId: (id: UUID | null) => void;
  toggleLayer: (layer: keyof LayerVisibility) => void;
  toggleSidebars: () => void;
  toast: (toast: Omit<ToastMessage, 'id'>) => void;
  dismissToast: (id: string) => void;
  setSimulationTimestamp: (timestamp: number | null) => void;
  setSimulationPlaying: (playing: boolean) => void;
  setSimulationSpeed: (speed: 1 | 5 | 10 | 20 | 60) => void;
  addSimulationOverride: (override: SimulationOverride) => void;
  clearSimulationOverrides: () => void;
  appendTraceEvent: (event: OptimizationTraceEvent) => void;
  clearTraceEvents: () => void;
}

const initialLayers: LayerVisibility = {
  base: true,
  zones: true,
  zoneBorders: true,
  warehouse: true,
  deliveries: true,
  pickups: true,
  unassigned: true,
  candidates: true,
  routes: true,
  traveled: true,
  activeLeg: true,
  trucks: true,
  corridor: false,
  selected: true,
  truckRestrictions: false,
};

export const useUiStore = create<UiState>((set) => ({
  mode: 'EDITOR',
  section: 'SCENARIO',
  mapTool: 'SELECT',
  selected: null,
  mapClickDraft: null,
  relationSourceZoneId: null,
  layers: initialLayers,
  sidebarsCollapsed: false,
  toasts: [],
  simulationTimestamp: null,
  simulationPlaying: false,
  simulationSpeed: 5,
  simulationOverrides: [],
  traceEvents: [],
  setMode: (mode) => set({ mode }),
  setSection: (section) => set({ section }),
  setMapTool: (mapTool) => set({ mapTool }),
  setSelected: (selected) => set({ selected }),
  setMapClickDraft: (mapClickDraft) => set({ mapClickDraft }),
  setRelationSourceZoneId: (relationSourceZoneId) => set({ relationSourceZoneId }),
  toggleLayer: (layer) => set((state) => ({ layers: { ...state.layers, [layer]: !state.layers[layer] } })),
  toggleSidebars: () => set((state) => ({ sidebarsCollapsed: !state.sidebarsCollapsed })),
  toast: (toast) =>
    set((state) => ({
      toasts: [...state.toasts.slice(-3), { ...toast, id: `${Date.now()}-${Math.random().toString(36).slice(2)}` }],
    })),
  dismissToast: (id) => set((state) => ({ toasts: state.toasts.filter((toast) => toast.id !== id) })),
  setSimulationTimestamp: (simulationTimestamp) => set({ simulationTimestamp }),
  setSimulationPlaying: (simulationPlaying) => set({ simulationPlaying }),
  setSimulationSpeed: (simulationSpeed) => set({ simulationSpeed }),
  addSimulationOverride: (override) =>
    set((state) => ({ simulationOverrides: [...state.simulationOverrides, override] })),
  clearSimulationOverrides: () => set({ simulationOverrides: [] }),
  appendTraceEvent: (event) => set((state) => ({ traceEvents: [...state.traceEvents.slice(-499), event] })),
  clearTraceEvents: () => set({ traceEvents: [] }),
}));
