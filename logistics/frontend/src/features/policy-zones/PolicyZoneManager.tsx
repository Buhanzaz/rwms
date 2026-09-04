import { Ban, MapPinned, Plus, Tag, Trash2, Truck } from 'lucide-react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import type { MultiPolygon } from 'geojson';
import { api } from '../../api/client';
import { Badge, Button, EmptyState, ErrorPanel, Field, Modal, SelectField } from '../../components/ui';
import type {
  PolicyZoneInput,
  PolicyZoneKind,
  Warehouse,
  WarehousePolicyZone,
} from '../../domain/types';
import { PolicyZoneMapEditor } from '../../map/PolicyZoneMapEditor';
import { userFacingErrorDetail } from '../../utils/user-facing-error';
import { parsePolicyZoneGeometry, policyZoneGeometryText } from './policy-zone-geometry';

const KIND_LABELS: Record<PolicyZoneKind, string> = {
  SPECIAL_PRICE: 'Особая цена',
  FORBIDDEN: 'Запрещено',
  NO_TRAILER: 'Без прицепа',
};
const KIND_COLORS: Record<PolicyZoneKind, string> = {
  SPECIAL_PRICE: '#3B82F6',
  FORBIDDEN: '#EF4444',
  NO_TRAILER: '#F59E0B',
};
const KIND_ICONS = {
  SPECIAL_PRICE: Tag,
  FORBIDDEN: Ban,
  NO_TRAILER: Truck,
} satisfies Record<PolicyZoneKind, typeof Tag>;

interface PolicyZoneDraft {
  id: string | null;
  version: number | null;
  name: string;
  kind: PolicyZoneKind;
  color: string;
  geometryText: string;
  geometry: MultiPolygon | null;
  deliveryPriceRubles: string;
  pickupPriceRubles: string;
  createIntentKey: string;
}

/** Dispatcher CRUD surface for exceptional warehouse policies only. */
export function PolicyZoneManager({ warehouse, onClose }: {
  warehouse: Warehouse;
  onClose: () => void;
}) {
  const [zones, setZones] = useState<WarehousePolicyZone[]>([]);
  const [draft, setDraft] = useState<PolicyZoneDraft>(() => emptyDraft());
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<unknown>(null);
  const [commandError, setCommandError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [deleteCandidate, setDeleteCandidate] = useState<string | null>(null);

  const loadZones = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      setZones(await api.listPolicyZones(warehouse.id));
    } catch (error: unknown) {
      setLoadError(error);
    } finally {
      setLoading(false);
    }
  }, [warehouse.id]);

  useEffect(() => {
    void loadZones();
  }, [loadZones]);

  const selected = useMemo(
    () => zones.find((zone) => zone.id === draft.id) ?? null,
    [draft.id, zones],
  );
  const selectZone = (zone: WarehousePolicyZone) => {
    setCommandError(null);
    setDeleteCandidate(null);
    setDraft(draftFromZone(zone));
  };
  const startCreate = () => {
    setCommandError(null);
    setDeleteCandidate(null);
    setDraft(emptyDraft());
  };
  const changeKind = (kind: PolicyZoneKind) => {
    setDraft((current) => ({
      ...current,
      kind,
      color: KIND_COLORS[kind],
      deliveryPriceRubles: kind === 'SPECIAL_PRICE' ? current.deliveryPriceRubles : '',
      pickupPriceRubles: kind === 'SPECIAL_PRICE' ? current.pickupPriceRubles : '',
    }));
  };
  const changeGeometryText = (geometryText: string) => {
    setDraft((current) => {
      let geometry = current.geometry;
      try {
        geometry = parsePolicyZoneGeometry(geometryText);
      } catch {
        // Keep the last valid preview while the operator finishes exact JSON editing.
      }
      return { ...current, geometryText, geometry };
    });
  };

  const save = async () => {
    setCommandError(null);
    let input: PolicyZoneInput;
    try {
      input = draftInput(draft);
    } catch (error: unknown) {
      setCommandError(error instanceof Error ? error.message : 'Проверьте параметры исключения.');
      return;
    }
    setBusy(true);
    try {
      const saved = draft.id && draft.version
        ? await api.updatePolicyZone(warehouse.id, draft.id, input, draft.version)
        : await api.createPolicyZone(warehouse.id, input, draft.createIntentKey);
      const authoritative = await api.listPolicyZones(warehouse.id);
      setZones(authoritative);
      setDraft(draftFromZone(authoritative.find((zone) => zone.id === saved.id) ?? saved));
    } catch (error: unknown) {
      setCommandError(userFacingErrorDetail(error, 'Не удалось сохранить исключение.'));
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    if (!draft.id || !draft.version) return;
    setCommandError(null);
    setBusy(true);
    try {
      await api.deletePolicyZone(warehouse.id, draft.id, draft.version);
      setZones(await api.listPolicyZones(warehouse.id));
      setDraft(emptyDraft());
      setDeleteCandidate(null);
    } catch (error: unknown) {
      setCommandError(userFacingErrorDetail(error, 'Не удалось удалить исключение.'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      wide
      title="Исключения на карте"
      description={`Склад «${warehouse.name}»: особые цены и ограничения маршрута`}
      onClose={onClose}
    >
      <div className="policy-zone-callout">
        <MapPinned size={20} aria-hidden="true" />
        <div>
          <strong>Обычная доставка остаётся изохронной</strong>
          <p>Ступени времени задают штатную цену, а последняя изохрона — жёсткий предел дальности. Эти полигоны действуют только как исключения внутри этого предела.</p>
        </div>
      </div>
      {loadError ? <ErrorPanel title="Не удалось загрузить исключения" error={loadError} onRetry={() => void loadZones()} /> : (
        <div className="policy-zone-manager">
          <aside className="policy-zone-manager__list" aria-label="Исключения склада">
            <Button type="button" variant="primary" onClick={startCreate} disabled={busy}>
              <Plus size={15} aria-hidden="true" />Новое исключение
            </Button>
            {loading ? <p className="policy-zone-manager__muted">Загружаем…</p> : null}
            {!loading && !zones.length ? (
              <EmptyState
                title="Исключений пока нет"
                description="Штатная доступность и цены определяются только изохронами."
              />
            ) : null}
            <div className="policy-zone-manager__cards">
              {zones.map((zone) => {
                const Icon = KIND_ICONS[zone.kind];
                return (
                  <button
                    type="button"
                    className={`policy-zone-card${draft.id === zone.id ? ' policy-zone-card--selected' : ''}`}
                    key={zone.id}
                    onClick={() => selectZone(zone)}
                  >
                    <span className="policy-zone-card__swatch" style={{ background: zone.color }} />
                    <span><strong>{zone.name}</strong><small><Icon size={12} aria-hidden="true" />{KIND_LABELS[zone.kind]}</small></span>
                    <Badge tone={zone.kind === 'FORBIDDEN' ? 'danger' : zone.kind === 'NO_TRAILER' ? 'warning' : 'accent'}>v{zone.version}</Badge>
                  </button>
                );
              })}
            </div>
          </aside>
          <div className="policy-zone-manager__editor">
            <header className="policy-zone-manager__editor-header">
              <div><strong>{selected ? selected.name : 'Новое исключение'}</strong><small>Точные значения и геометрия сохраняются на сервере</small></div>
              {selected ? <Badge>{KIND_LABELS[selected.kind]}</Badge> : null}
            </header>
            <div className="policy-zone-manager__fields">
              <Field
                label="Название"
                value={draft.name}
                maxLength={200}
                disabled={busy}
                onChange={(event) => setDraft((current) => ({ ...current, name: event.target.value }))}
              />
              <SelectField
                label="Правило"
                value={draft.kind}
                disabled={busy}
                onChange={(event) => changeKind(event.target.value as PolicyZoneKind)}
              >
                <option value="SPECIAL_PRICE">Особая цена</option>
                <option value="FORBIDDEN">Запрещено</option>
                <option value="NO_TRAILER">Только без прицепа</option>
              </SelectField>
              <Field
                label="Цвет"
                type="color"
                value={draft.color}
                disabled={busy}
                onChange={(event) => setDraft((current) => ({ ...current, color: event.target.value.toUpperCase() }))}
              />
            </div>
            {draft.kind === 'SPECIAL_PRICE' ? (
              <div className="policy-zone-manager__prices">
                <Field
                  label="Доставка, ₽"
                  type="number"
                  min="0"
                  step="1"
                  value={draft.deliveryPriceRubles}
                  disabled={busy}
                  onChange={(event) => setDraft((current) => ({ ...current, deliveryPriceRubles: event.target.value }))}
                />
                <Field
                  label="Вывоз, ₽"
                  type="number"
                  min="0"
                  step="1"
                  value={draft.pickupPriceRubles}
                  disabled={busy}
                  onChange={(event) => setDraft((current) => ({ ...current, pickupPriceRubles: event.target.value }))}
                />
                <p>Цена заменяет тариф достигнутой изохроны, но не расширяет её дальность.</p>
              </div>
            ) : draft.kind === 'FORBIDDEN' ? (
              <p className="policy-zone-rule policy-zone-rule--danger"><Ban size={16} aria-hidden="true" />Точки внутри полигона недоступны для новых слотов и планирования.</p>
            ) : (
              <p className="policy-zone-rule policy-zone-rule--warning"><Truck size={16} aria-hidden="true" />Точки внутри полигона планируются только без прицепа.</p>
            )}
            <PolicyZoneMapEditor
              warehouse={warehouse}
              zones={zones}
              selectedZoneId={draft.id}
              geometry={draft.geometry}
              color={draft.color}
              disabled={busy}
              onGeometryChange={(geometry) => setDraft((current) => ({
                ...current,
                geometry,
                geometryText: policyZoneGeometryText(geometry),
              }))}
            />
            <label className="field policy-zone-manager__geojson">
              <span className="field__label">Точная геометрия GeoJSON MultiPolygon</span>
              <textarea
                className="input"
                spellCheck={false}
                value={draft.geometryText}
                disabled={busy}
                placeholder={'{\n  "type": "MultiPolygon",\n  "coordinates": [...]\n}'}
                onChange={(event) => changeGeometryText(event.target.value)}
              />
              <span className="field__hint">Координаты WGS84: [долгота, широта]. Сервер проверит замыкание и самопересечения.</span>
            </label>
            {commandError ? <p className="policy-zone-manager__error" role="alert">{commandError}</p> : null}
            <footer className="policy-zone-manager__actions">
              {selected && deleteCandidate !== selected.id ? (
                <Button type="button" variant="danger" disabled={busy} onClick={() => setDeleteCandidate(selected.id)}>
                  <Trash2 size={14} aria-hidden="true" />Удалить
                </Button>
              ) : null}
              {selected && deleteCandidate === selected.id ? (
                <div className="policy-zone-manager__delete-confirm" role="alert">
                  <span>Удалить «{selected.name}»?</span>
                  <Button type="button" size="sm" onClick={() => setDeleteCandidate(null)}>Нет</Button>
                  <Button type="button" size="sm" variant="danger" disabled={busy} onClick={() => void remove()}>Да, удалить</Button>
                </div>
              ) : null}
              <span className="policy-zone-manager__actions-spacer" />
              <Button type="button" onClick={onClose}>Закрыть</Button>
              <Button type="button" variant="primary" disabled={busy} onClick={() => void save()}>
                {busy ? 'Сохраняем…' : selected ? 'Сохранить изменения' : 'Создать исключение'}
              </Button>
            </footer>
          </div>
        </div>
      )}
    </Modal>
  );
}

function emptyDraft(): PolicyZoneDraft {
  return {
    id: null,
    version: null,
    name: '',
    kind: 'SPECIAL_PRICE',
    color: KIND_COLORS.SPECIAL_PRICE,
    geometryText: '',
    geometry: null,
    deliveryPriceRubles: '',
    pickupPriceRubles: '',
    createIntentKey: crypto.randomUUID(),
  };
}

function draftFromZone(zone: WarehousePolicyZone): PolicyZoneDraft {
  return {
    id: zone.id,
    version: zone.version,
    name: zone.name,
    kind: zone.kind,
    color: zone.color,
    geometryText: policyZoneGeometryText(zone.geometry),
    geometry: zone.geometry,
    deliveryPriceRubles: zone.delivery_price_rubles?.toString() ?? '',
    pickupPriceRubles: zone.pickup_price_rubles?.toString() ?? '',
    createIntentKey: crypto.randomUUID(),
  };
}

function draftInput(draft: PolicyZoneDraft): PolicyZoneInput {
  const name = draft.name.trim();
  if (!name) throw new Error('Укажите название исключения.');
  if (!/^#[0-9A-F]{6}$/u.test(draft.color)) throw new Error('Укажите цвет в формате #RRGGBB.');
  const geometry = parsePolicyZoneGeometry(draft.geometryText);
  if (draft.kind !== 'SPECIAL_PRICE') {
    return {
      name,
      kind: draft.kind,
      color: draft.color,
      geometry,
      delivery_price_rubles: null,
      pickup_price_rubles: null,
    };
  }
  return {
    name,
    kind: draft.kind,
    color: draft.color,
    geometry,
    delivery_price_rubles: parseRubles(draft.deliveryPriceRubles, 'доставки'),
    pickup_price_rubles: parseRubles(draft.pickupPriceRubles, 'вывоза'),
  };
}

function parseRubles(value: string, label: string): number {
  if (!/^\d+$/u.test(value)) throw new Error(`Укажите целую неотрицательную цену ${label}.`);
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed)) throw new Error(`Цена ${label} слишком велика.`);
  return parsed;
}
