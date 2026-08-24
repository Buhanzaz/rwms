import { ChevronFirst, ChevronLast, Pause, Play, SkipBack, SkipForward, Square } from 'lucide-react';
import { useEffect, useMemo } from 'react';
import type { RoutePlan, SimulationDerivedState, SimulationOverride } from '../../domain/types';
import { Button } from '../../components/ui';
import { planTimeBounds, simulationEventTimestamps } from '../../simulation/deriveSimulationState';
import { formatTime } from '../../utils/format';

const speeds = [1, 5, 10, 20, 60] as const;

export function SimulationBar({ plan, state, timestamp, timeZone, playing, speed, overrides, onTimestamp, onPlaying, onSpeed }: {
  plan: RoutePlan;
  state: SimulationDerivedState;
  timestamp: number;
  timeZone: string;
  playing: boolean;
  speed: 1 | 5 | 10 | 20 | 60;
  overrides: SimulationOverride[];
  onTimestamp: (timestamp: number) => void;
  onPlaying: (playing: boolean) => void;
  onSpeed: (speed: 1 | 5 | 10 | 20 | 60) => void;
}) {
  const bounds = planTimeBounds(plan);
  const events = useMemo(() => simulationEventTimestamps(plan, overrides), [overrides, plan]);
  useEffect(() => {
    if (!playing || !bounds) return;
    let previous = performance.now();
    const timer = window.setInterval(() => {
      const now = performance.now();
      const next = Math.min(bounds.end, timestamp + (now - previous) * speed);
      previous = now;
      onTimestamp(next);
      if (next >= bounds.end) onPlaying(false);
    }, 100);
    return () => window.clearInterval(timer);
  }, [bounds, onPlaying, onTimestamp, playing, speed, timestamp]);
  if (!bounds) return null;

  const jumpEvent = (direction: -1 | 1) => {
    const candidates = direction === 1 ? events.filter((value) => value > timestamp) : events.filter((value) => value < timestamp).reverse();
    onTimestamp(candidates[0] ?? (direction === 1 ? bounds.end : bounds.start));
  };
  const time = new Intl.DateTimeFormat('ru-RU', { timeZone, hour: '2-digit', minute: '2-digit', second: '2-digit' }).format(new Date(timestamp));
  return (
    <footer className="simulation-bar" aria-label="Управление симуляцией">
      <div className="simulation-controls">
        <Button size="sm" onClick={() => { onPlaying(false); onTimestamp(bounds.start); }} aria-label="В начало"><ChevronFirst size={17} /></Button>
        <Button size="sm" onClick={() => jumpEvent(-1)} aria-label="Предыдущее событие"><SkipBack size={17} /></Button>
        <Button variant="primary" size="sm" onClick={() => onPlaying(!playing)} aria-label={playing ? 'Пауза' : 'Запустить симуляцию'}>{playing ? <Pause size={17} /> : <Play size={17} />}</Button>
        <Button size="sm" onClick={() => { onPlaying(false); onTimestamp(bounds.start); }} aria-label="Остановить"><Square size={15} /></Button>
        <Button size="sm" onClick={() => jumpEvent(1)} aria-label="Следующее событие"><SkipForward size={17} /></Button>
        <Button size="sm" onClick={() => onTimestamp(bounds.end)} aria-label="В конец"><ChevronLast size={17} /></Button>
      </div>
      <div className="simulation-time">
        <div className="simulation-time__labels"><span>{formatTime(bounds.start, timeZone)}</span><strong className="simulation-time__current" data-testid="simulation-current-time">{time}</strong><span>{formatTime(bounds.end, timeZone)}</span></div>
        <input aria-label="Время симуляции" type="range" min={bounds.start} max={bounds.end} step={1000} value={timestamp} onChange={(event) => { onPlaying(false); onTimestamp(Number(event.target.value)); }} />
        <div className="entity-card__row">
          <span>{state.vehicles.filter((vehicle) => vehicle.status !== 'FINISHED').length} машин в работе</span>
          <label className="checkbox-field">Скорость <select className="input" aria-label="Скорость симуляции" value={speed} onChange={(event) => onSpeed(Number(event.target.value) as 1 | 5 | 10 | 20 | 60)}>{speeds.map((value) => <option value={value} key={value}>{value}×</option>)}</select></label>
        </div>
      </div>
      <div className="simulation-events" aria-label="Журнал событий">
        {state.events.slice(-12).reverse().map((event) => <p key={event.id}><time>{formatTime(event.timestamp, timeZone)}</time>{event.label}</p>)}
        {!state.events.length ? <p>До первого события</p> : null}
      </div>
    </footer>
  );
}
