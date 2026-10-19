import { useEffect, useMemo, useState, type CSSProperties } from 'react';

export interface MapInsets { top: number; right: number; bottom: number; left: number }

/** Keep camera targets inside the exposed map without rebuilding its WebGL instance. */
export function mapLayout(width: number, height: number, chromeHeight: number, inspectorWidth: number, panelOpen: boolean, simulation: boolean, simulationHeight = 100) {
  const mobile = width <= 760;
  const top = chromeHeight + 24;
  const bottom = (mobile ? 72 : 12) + (simulation ? simulationHeight + 12 : 0);
  const sidebarWidth = mobile ? 0 : width <= 1250 ? 64 : 248;
  const panelWidth = mobile ? width - 24 : Math.min(inspectorWidth, Math.floor(width / 2));
  const panelTop = mobile ? Math.max(top, Math.round(height * .45)) : top;
  const controlsBottom = mobile && panelOpen ? height - panelTop + 12 : bottom;
  const padding: MapInsets = {
    top: top + 60,
    left: mobile ? 16 : sidebarWidth + 36,
    right: !mobile && panelOpen ? panelWidth + 36 : 24,
    bottom: mobile && panelOpen ? height - panelTop + 12 : bottom + 24,
  };
  // Short windows must still leave a non-zero camera viewport.
  for (const [first, second, size] of [['left', 'right', width], ['top', 'bottom', height]] as const) {
    const total = padding[first] + padding[second];
    const scale = Math.min(1, Math.max(0, size - 80) / total);
    padding[first] = Math.floor(padding[first] * scale);
    padding[second] = Math.floor(padding[second] * scale);
  }
  return {
    padding,
    panelWidth,
    style: {
      '--workspace-top': `${top}px`,
      '--workspace-bottom': `${bottom}px`,
      '--panel-top': `${panelTop}px`,
      '--sidebar-width': `${sidebarWidth}px`,
      '--inspector-width': `${panelWidth}px`,
      '--map-controls-left': `${mobile ? 12 : sidebarWidth + 24}px`,
      '--map-controls-right': `${!mobile && panelOpen ? panelWidth + 24 : 12}px`,
      '--map-controls-bottom': `${controlsBottom}px`,
    } as CSSProperties,
  };
}

export function useMapLayout(inspectorWidth: number, panelOpen: boolean, simulation: boolean) {
  const [chrome, setChrome] = useState<HTMLDivElement | null>(null);
  const [simulationElement, setSimulationElement] = useState<HTMLDivElement | null>(null);
  const [viewport, setViewport] = useState(() => ({ width: window.innerWidth, height: window.innerHeight, chromeHeight: 64, simulationHeight: 100 }));
  useEffect(() => {
    const measure = () => setViewport({ width: window.innerWidth, height: window.innerHeight, chromeHeight: chrome?.getBoundingClientRect().height || 64, simulationHeight: simulationElement?.getBoundingClientRect().height || 100 });
    measure();
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(measure);
    if (chrome) observer?.observe(chrome);
    if (simulationElement) observer?.observe(simulationElement);
    window.addEventListener('resize', measure);
    return () => { observer?.disconnect(); window.removeEventListener('resize', measure); };
  }, [chrome, simulationElement]);
  const layout = useMemo(() => mapLayout(viewport.width, viewport.height, viewport.chromeHeight, inspectorWidth, panelOpen, simulation, viewport.simulationHeight), [viewport, inspectorWidth, panelOpen, simulation]);
  return { ...layout, chromeRef: setChrome, simulationRef: setSimulationElement };
}
