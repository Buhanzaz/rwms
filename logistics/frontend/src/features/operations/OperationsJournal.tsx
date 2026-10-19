import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { RefreshCw, ScrollText } from 'lucide-react';
import { api } from '../../api/client';
import type { UUID } from '../../domain/types';
import { Button, EmptyState, ErrorPanel, Spinner } from '../../components/ui';
import { formatDate, formatTime } from '../../utils/format';
import { operationalHistoryEntries } from './operations-history';

interface OperationsJournalProps {
  warehouseId: UUID;
  planningDate: string;
  timeZone: string;
  onSelectRequest: (requestId: UUID) => void;
}

/** Load the selected day's journal on demand next to the operating plan. */
export function OperationsJournalDisclosure(props: OperationsJournalProps) {
  const [open, setOpen] = useState(false);
  return <section className="operations-journal-disclosure">
    <Button type="button" aria-expanded={open} onClick={() => setOpen(!open)}><ScrollText size={15} aria-hidden="true" />{open ? 'Скрыть журнал дня' : 'Журнал дня'}</Button>
    {open ? <OperationsJournal {...props} /> : null}
  </section>;
}

/** Server history for the selected warehouse and operating day. */
export function OperationsJournal({
  warehouseId,
  planningDate,
  timeZone,
  onSelectRequest,
}: OperationsJournalProps) {
  const query = useQuery({
    queryKey: ['planning-day-operations', warehouseId, planningDate],
    queryFn: () => api.getPlanningDayOperations(warehouseId, planningDate),
    retry: false,
    refetchInterval: 15_000,
  });
  const entries = query.data ? operationalHistoryEntries(query.data) : [];

  return (
    <section className="operations-journal" aria-labelledby="operations-journal-title">
      <header className="operations-journal__header">
        <div>
          <span className="operations-journal__eyebrow"><ScrollText size={15} aria-hidden="true" />История операций</span>
          <h2 id="operations-journal-title">Журнал за {formatDate(planningDate)}</h2>
          <p className="section-subtitle">Комментарии сервиса и решения логиста. Записи хранятся на сервере и не удаляются вместе с уведомлениями.</p>
        </div>
        <Button
          size="sm"
          variant="ghost"
          disabled={query.isFetching}
          onClick={() => void query.refetch()}
          aria-label="Обновить журнал"
        >
          <RefreshCw size={14} aria-hidden="true" />
        </Button>
      </header>

      {query.isPending ? <Spinner label="Загружаем журнал…" /> : null}
      {query.isError ? <ErrorPanel title="Журнал недоступен" error={query.error} onRetry={() => void query.refetch()} /> : null}
      {query.data?.truncated_collections?.length ? (
        <div className="operations-inline-warning" role="status">
          Показано только последнее доступное окно истории за выбранный день.
        </div>
      ) : null}

      {!query.isPending && !query.isError ? (
        <div className="operations-journal__list" aria-label="Записи журнала">
          {[...entries].reverse().map((entry) => (
            <article className={`notification-history notification-history--${entry.tone}`} key={entry.key}>
              <strong>{entry.title}</strong>
              <p>{entry.detail}</p>
              <time>{formatTime(entry.createdAt, timeZone)}</time>
              {entry.requestId ? (
                <Button
                  type="button"
                  size="sm"
                  variant="ghost"
                  onClick={() => onSelectRequest(entry.requestId as UUID)}
                >
                  Показать заявку
                </Button>
              ) : null}
            </article>
          ))}
          {!entries.length ? (
            <EmptyState
              title="Записей пока нет"
              description="Здесь появятся фактические события, комментарии сервиса и сохранённые решения логиста."
            />
          ) : null}
        </div>
      ) : null}
    </section>
  );
}
