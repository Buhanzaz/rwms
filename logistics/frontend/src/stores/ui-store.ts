import { create } from 'zustand';
import type { MapClickDraft, MapSelection, OptimizationTraceEvent, SimulationOverride } from '../domain/types';

export type AppMode = 'EDITOR' | 'PLAN' | 'SIMULATION';
export type ThemeMode = 'light' | 'dark';
export type LeftSection =
  | 'WAREHOUSE'
  | 'ZONES'
  | 'DRIVERS'
  | 'VEHICLES'
  | 'SHIFTS'
  | 'REQUESTS'
  | 'PLAN_DAY'
  | 'ROUTES'
  | 'UNASSIGNED'
  | 'SETTINGS';
export type MapTool = 'SELECT' | 'ADD_DELIVERY' | 'ADD_PICKUP' | 'DRAW_ZONE' | 'CUT_ZONE';

export interface LayerVisibility {
  base: boolean;
  zones: boolean;
  zoneBorders: boolean;
  warehouse: boolean;
  warehouseIsochrones: boolean;
  taskIsochrones: boolean;
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

/** One bounded in-memory notification shown briefly and retained in bell history. */
export interface NotificationMessage {
  id: string;
  tone: 'success' | 'warning' | 'error' | 'info';
  title: string;
  detail?: string;
  replacementKey?: string;
  createdAt: string;
  visible: boolean;
  read: boolean;
}

interface UiState {
  theme: ThemeMode;
  mode: AppMode;
  section: LeftSection;
  mapTool: MapTool;
  selected: MapSelection;
  mapClickDraft: MapClickDraft | null;
  layers: LayerVisibility;
  sidebarsCollapsed: boolean;
  notifications: NotificationMessage[];
  notificationDurationSeconds: number;
  simulationTimestamp: number | null;
  simulationPlaying: boolean;
  simulationSpeed: 1 | 5 | 10 | 20 | 60;
  simulationOverrides: SimulationOverride[];
  traceEvents: OptimizationTraceEvent[];
  setTheme: (theme: ThemeMode) => void;
  setMode: (mode: AppMode) => void;
  setSection: (section: LeftSection) => void;
  setMapTool: (tool: MapTool) => void;
  setSelected: (selected: MapSelection) => void;
  setMapClickDraft: (draft: MapClickDraft | null) => void;
  toggleLayer: (layer: keyof LayerVisibility) => void;
  toggleSidebars: () => void;
  toast: (toast: Omit<NotificationMessage, 'id' | 'createdAt' | 'visible' | 'read'>) => void;
  dismissToast: (id: string) => void;
  clearNotifications: () => void;
  markNotificationsRead: () => void;
  setNotificationDurationSeconds: (seconds: number) => void;
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
  warehouseIsochrones: false,
  taskIsochrones: false,
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

const NOTIFICATION_DURATION_KEY = 'rwms-logistics-notification-duration-seconds';
const THEME_KEY = 'rwms-logistics-theme';

function readTheme(): ThemeMode {
  if (typeof window === 'undefined') return 'dark';
  try {
    const stored = window.localStorage.getItem(THEME_KEY);
    if (stored === 'light' || stored === 'dark') return stored;
  } catch {
    // Fall through to the operating-system preference.
  }
  return window.matchMedia?.('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
}

function applyTheme(theme: ThemeMode): void {
  if (typeof document === 'undefined') return;
  document.documentElement.dataset.theme = theme;
  document.documentElement.style.colorScheme = theme;
}

function writeTheme(theme: ThemeMode): void {
  applyTheme(theme);
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(THEME_KEY, theme);
  } catch {
    // UI preferences remain in memory when browser storage is unavailable.
  }
}

const initialTheme = readTheme();
applyTheme(initialTheme);

function readNotificationDuration(): number {
  if (typeof window === 'undefined') return 8;
  try {
    const parsed = Number(window.localStorage.getItem(NOTIFICATION_DURATION_KEY));
    return Number.isFinite(parsed) && parsed >= 1 && parsed <= 60 ? Math.round(parsed) : 8;
  } catch {
    return 8;
  }
}

function writeNotificationDuration(seconds: number): void {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(NOTIFICATION_DURATION_KEY, String(seconds));
  } catch {
    // UI preferences remain in memory when browser storage is unavailable.
  }
}

export const useUiStore = create<UiState>((set) => ({
  theme: initialTheme,
  mode: 'EDITOR',
  section: 'WAREHOUSE',
  mapTool: 'SELECT',
  selected: null,
  mapClickDraft: null,
  layers: initialLayers,
  sidebarsCollapsed: false,
  notifications: [],
  notificationDurationSeconds: readNotificationDuration(),
  simulationTimestamp: null,
  simulationPlaying: false,
  simulationSpeed: 5,
  simulationOverrides: [],
  traceEvents: [],
  setTheme: (theme) => {
    writeTheme(theme);
    set({ theme });
  },
  setMode: (mode) => set({ mode }),
  setSection: (section) => set({ section }),
  setMapTool: (mapTool) => set({ mapTool }),
  setSelected: (selected) => set({ selected }),
  setMapClickDraft: (mapClickDraft) => set({ mapClickDraft }),
  toggleLayer: (layer) => set((state) => ({ layers: { ...state.layers, [layer]: !state.layers[layer] } })),
  toggleSidebars: () => set((state) => ({ sidebarsCollapsed: !state.sidebarsCollapsed })),
  toast: (toast) =>
    set((state) => {
      const createdAt = new Date().toISOString();
      const existing = toast.replacementKey
        ? state.notifications.find((current) => current.replacementKey === toast.replacementKey)
        : undefined;
      const notification: NotificationMessage = {
        ...toast,
        id: existing?.id ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`,
        createdAt,
        visible: true,
        read: false,
      };
      const retained = existing
        ? state.notifications.filter((current) => current.id !== existing.id)
        : state.notifications;
      return {
        notifications: [...retained.slice(-199), notification],
      };
    }),
  dismissToast: (id) => set((state) => ({
    notifications: state.notifications.map((notification) => notification.id === id
      ? { ...notification, visible: false }
      : notification),
  })),
  clearNotifications: () => set({ notifications: [] }),
  markNotificationsRead: () => set((state) => ({
    notifications: state.notifications.map((notification) => ({ ...notification, read: true })),
  })),
  setNotificationDurationSeconds: (seconds) => {
    const normalized = Math.min(60, Math.max(1, Math.round(seconds)));
    writeNotificationDuration(normalized);
    set({ notificationDurationSeconds: normalized });
  },
  setSimulationTimestamp: (simulationTimestamp) => set({ simulationTimestamp }),
  setSimulationPlaying: (simulationPlaying) => set({ simulationPlaying }),
  setSimulationSpeed: (simulationSpeed) => set({ simulationSpeed }),
  addSimulationOverride: (override) =>
    set((state) => ({ simulationOverrides: [...state.simulationOverrides, override] })),
  clearSimulationOverrides: () => set({ simulationOverrides: [] }),
  appendTraceEvent: (event) => set((state) => ({ traceEvents: [...state.traceEvents.slice(-499), event] })),
  clearTraceEvents: () => set({ traceEvents: [] }),
}));
