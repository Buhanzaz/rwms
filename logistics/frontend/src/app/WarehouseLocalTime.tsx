import { useEffect, useMemo, useState } from 'react';
import { formatWarehouseLocalTime } from '../utils/format';

/** Live warehouse clock that immediately follows a selected warehouse change. */
export function WarehouseLocalTime({ timeZone }: { timeZone: string }) {
  const [now, setNow] = useState(() => new Date());

  useEffect(() => {
    setNow(new Date());
    const interval = window.setInterval(() => setNow(new Date()), 30_000);
    return () => window.clearInterval(interval);
  }, [timeZone]);

  const time = useMemo(() => formatWarehouseLocalTime(now, timeZone), [now, timeZone]);
  return <small className="topbar__warehouse-local-time">{time} · {timeZone}</small>;
}
