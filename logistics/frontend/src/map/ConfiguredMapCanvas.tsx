import { useEffect } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { getMapSettings } from '../api/map-settings';
import { Button } from '../components/ui';
import { MapCanvas, type MapCanvasProps } from './MapCanvas';

const queryKey = ['map-display-settings'] as const;

/** Invalidation carries only a version; credentials are always read from the owner. */
export function ConfiguredMapCanvas(props: Omit<MapCanvasProps, 'mapSettings'>) {
  const client = useQueryClient();
  const query = useQuery({
    queryKey,
    queryFn: ({ signal }) => getMapSettings(signal),
    retry: 2,
    refetchInterval: (query) => (query.state.status === 'error' ? false : 5_000),
    refetchOnWindowFocus: true,
    refetchOnReconnect: true,
    gcTime: 0,
  });

  useEffect(() => {
    if (typeof BroadcastChannel === 'undefined') return;
    let channel: BroadcastChannel;
    try {
      channel = new BroadcastChannel('rwms-logistics-map-settings');
    } catch {
      return;
    }
    channel.onmessage = (event: MessageEvent<unknown>) => {
      const data = event.data as { type?: unknown; version?: unknown } | null;
      if (data?.type === 'changed' && Number.isSafeInteger(data.version) && Number(data.version) > 0) {
        void client.invalidateQueries({ queryKey, exact: true });
      }
    };
    return () => channel.close();
  }, [client]);

  const error = query.isError ? (
    <div className="map-overlay map-settings-error" role="alert">
      <span>{query.error.message}</span>
      <Button onClick={() => void query.refetch()}>Повторить загрузку настроек карты</Button>
    </div>
  ) : null;
  if (!query.data) {
    return (
      <main className="map-stage" aria-label="Логистическая карта">
        {error ?? (
          <div className="map-overlay progress-card" role="status">
            Загружаем настройки карты…
          </div>
        )}
      </main>
    );
  }
  return (
    <>
      <MapCanvas {...props} mapSettings={query.data} />
      {error}
    </>
  );
}
