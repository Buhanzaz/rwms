# Panel Estimates And Repairs Settings

Date: 2026-07-09.

Scope:

- `panel` route `/settings/estimates-repairs`.
- Legacy Jmix estimate catalog settings menu and section views.

## Legacy Evidence

- `wms-panel-old/src/main/resources/dev/buhanzaz/wmspanel/menu.xml`
  - `estimateCatalogSettings` contains four user-facing estimate catalog settings items:
    - `RepairEstimateCatalogCanvas.view`
    - `RepairEstimateCatalogWork.view`
    - `RepairEstimateCatalogMaterial.view`
    - `RepairEstimateCatalogFurniture.view`
- `wms-panel-old/src/main/resources/dev/buhanzaz/wmspanel/messages_ru.properties`
  - `RepairEstimateCatalogCanvasView.title=Конструктор каталога смет`
  - `RepairEstimateWorkCatalogView.title=Работы`
  - `RepairEstimateMaterialCatalogView.title=Материалы`
  - `RepairEstimateFurnitureCatalogView.title=Мебель`
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/view/repairestimatecatalogcanvas/RepairEstimateCatalogCanvasView.java`
  - Canvas editor works with catalog categories, nodes, links, link types, node positions, and save validation.
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/view/repairestimatecatalogsection/RepairEstimateWorkCatalogView.java`
  - Work section uses node type `WORK` and excludes furniture categories.
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/view/repairestimatecatalogsection/RepairEstimateMaterialCatalogView.java`
  - Material section uses node type `MATERIAL` and excludes furniture tree nodes.
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/view/repairestimatecatalogsection/RepairEstimateFurnitureCatalogView.java`
  - Furniture section uses node type `MATERIAL` and includes only furniture categories/tree nodes.

## Implemented In Panel

- Added `panel/src/features/settings/estimates-repairs/estimates-repairs-settings-page.tsx`.
- Connected `/settings/estimates-repairs` to the new page instead of the generic empty page.
- The page is split into two equal vertical sections:
  - `Настройка смет`
  - `Настройка ремонтов`
- `Настройка смет` contains the four legacy-derived buttons:
  - `Конструктор каталога смет`
  - `Работы`
  - `Материалы`
  - `Мебель`
- Added separate mock API files per legacy estimate catalog class:
  - `repair-estimate-catalog-canvas-settings-api.ts`
  - `repair-estimate-work-catalog-settings-api.ts`
  - `repair-estimate-material-catalog-settings-api.ts`
  - `repair-estimate-furniture-catalog-settings-api.ts`
- Added `repair-settings-api.ts` for temporary repair settings buttons.

## Target Architecture Notes

- Keep settings API modules separated by future microservice/class boundary. Do not collapse estimate catalog canvas, work catalog, material catalog, furniture catalog, and repair settings into one generic endpoint unless a real backend contract requires it.
- The current repair settings buttons are placeholders and must remain marked mock-only until a legacy-backed or product-backed repair settings contract is defined.
- Estimate catalog buttons are route/menu-level migration only. Full catalog editor behavior is not implemented in `panel` yet.

## 2026-07-09 Full Estimate Catalog Mock Update

Status: supersedes the earlier route/menu-only note for estimate catalog settings.

Implemented:

- Added a shared repair estimate catalog mock store behind separate adapter modules for:
  - constructor/canvas settings;
  - work catalog settings;
  - material catalog settings;
  - furniture catalog settings.
- Seeded the mock from legacy Liquibase catalog data. Current seed contains 185 catalog nodes and parent relationships from `PARENT_ID`/category update changelogs.
- Preserved the legacy split:
  - `Работы`: node type `WORK`, non-furniture categories only.
  - `Материалы`: node type `MATERIAL`, non-furniture categories only.
  - `Мебель`: node type `MATERIAL`, furniture category/tree only.
  - `Конструктор каталога смет`: category/node/link editing surface.
- Kept final DB graph link seed empty because the legacy `2026/06/28-180000-repair-estimate-catalog-canvas.xml` reset deletes all links and no later changelog inserts new `REPAIR_ESTIMATE_CATALOG_LINK` rows.
- Codes and `sortOrder` remain stored in the mock model, but default category cards and catalog tables do not display technical codes or sort order.
- Codes are visible only inside detail/edit dialogs where the user deliberately opens a catalog record.
- Catalog section tables support user sorting by visible business columns and persist table sort state in localStorage by section/category.
- Material and furniture section tables do not show duration/time columns. The `Длительность, мин` field appears only for work nodes.
- `Мебельная категория` is shown only for category nodes in the constructor context. It is hidden for work, material, and furniture item dialogs.
- Constructor cards show operational data instead of the old `Смета` badge: default quantity, unit, active flag, optional `Общий`, and comment.
- The constructor keeps the selected parent category visible on the canvas, but the `+ Блок` type selector excludes `Категория`; categories are parent entities and are created from the category menu only.
- Canvas links now follow the legacy anchor contract:
  - node cards expose top and bottom link points;
  - links are created by selecting/dragging from a point on the source block to a point on the target block;
  - selected link type is chosen in the constructor toolbar (`Путь` or `Зависимость`);
  - anchor sides are stored in link comment as `__anchors__:SOURCE->TARGET`, matching legacy.
- The separate `Связь` creation button/menu was removed; links are created directly through canvas points. Existing selected links can still be edited/deleted through the selected-link actions.
- Because the final legacy seed has zero explicit `REPAIR_ESTIMATE_CATALOG_LINK` rows after reset, the current React mock derives initial visible `Путь` links from `parentId` hierarchy (`parent -> child`) and stores default anchors as `__anchors__:BOTTOM->TOP`. This is target mock behavior for operator-visible canvas continuity, not proof that the final backend must materialize parent relationships as link rows.
- Constructor blocks are draggable with mouse/pointer input and persist coordinates through the existing node save adapter (`canvasX`, `canvasY`). Block titles wrap instead of truncating, and every card has a full-width bottom `Редактировать` action that opens the same node edit dialog as the toolbar action.
- Clicking a constructor link/arrow selects it and shows a centered overlay `Удалить` button on the link curve. The button is neutral by default and uses dark gray border/text hover styling; only the trash icon changes to solid destructive red on hover. It deletes through the same link delete adapter as the toolbar action.
- Constructor link creation shows a live foreground SVG preview while dragging from one top/bottom point toward another point.
- Constructor toolbar controls under the category title use equal button/select dimensions. Technical legacy view IDs such as `RepairEstimateCatalogCanvas.view` are not displayed. The old bottom duplicate action row for edit/delete block/link actions is removed.

Additional source evidence:

- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/entity/RepairEstimateCatalogNode.java`
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/entity/RepairEstimateCatalogLink.java`
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/service/RepairCatalogService.java`
- `wms-panel-old/src/main/java/dev/buhanzaz/wmspanel/view/repairestimatecatalogsection/AbstractRepairEstimateCatalogSectionView.java`
- `wms-panel-old/src/main/resources/dev/buhanzaz/wmspanel/liquibase/changelog/2026/06/28-180000-repair-estimate-catalog-canvas.xml`
- `wms-panel-old/src/main/resources/dev/buhanzaz/wmspanel/liquibase/changelog/2026/07/01-151000-repair-catalog-price-item-categories.xml`
- `wms-panel-old/src/main/resources/dev/buhanzaz/wmspanel/liquibase/changelog/2026/07/03-001000-repair-estimate-catalog-furniture-flag.xml`

Target notes:

- Keep the separate adapter files even though they share one mock store today; this preserves future microservice/backend class boundaries.
- Do not promote localStorage persistence keys to backend contract names.
- Table sorting state is target UI behavior for operator convenience, not legacy DB ordering.
- `RepairEstimateCatalogLinkType.DEPENDENCY` is displayed in React as `Зависимость`; legacy storage value remains `DEPENDENCY`.

## Verification

- `npm run typecheck` passed in `panel`.
- `npm run lint` passed in `panel`.
- `npm run build` passed in `panel`; only the existing Vite chunk-size warning was emitted.
- Browser validation on `http://127.0.0.1:8080/settings/estimates-repairs` confirmed:
  - the generic empty page is gone;
  - header shows `Настройка смет и ремонтов`;
  - the two sections are `Настройка смет` and `Настройка ремонтов`;
  - estimate settings show exactly four buttons: `Конструктор каталога смет`, `Работы`, `Материалы`, `Мебель`;
  - repair settings show four test buttons: `Этапы ремонта`, `Маршрут ремонта`, `Приёмка ремонта`, `Доработки`;
  - both sections render at equal height and no horizontal overflow appears after clicking `Работы`.
- Browser validation after clearing `rwms:repair-estimate-catalog:v1` confirmed the constructor backfills 177 derived mock links and renders 38 SVG arrow paths in the `Двери` category.
- Browser validation confirmed dragging a `Двери` child block updates its DOM position and persists `canvasX`/`canvasY` in `rwms:repair-estimate-catalog:v1`; card edit buttons and wrapped long titles render without title overflow.
- Browser validation confirmed clicking a link shows one absolute overlay `Удалить` button, highlights the selected arrow, uses dark gray button hover classes, keeps red fill/text off the button itself, and applies solid destructive red hover only to the trash icon.
- Browser validation confirmed constructor toolbar controls render at equal `208x36` dimensions, legacy `.view` labels are absent, bottom duplicate action buttons are absent, and the link preview path appears during point-to-point dragging.

## 2026-07-09 Legacy HSQLDB Catalog Link Sync

Status: supersedes the earlier Liquibase-only catalog seed conclusion.

Findings:

- The working legacy HSQLDB script `old_db/hsqldb/wmspanel.script` contains live estimate catalog data:
  - 232 `REPAIR_ESTIMATE_CATALOG_NODE` rows;
  - 254 `REPAIR_ESTIMATE_CATALOG_LINK` rows;
  - 182 `FOLLOW_UP` links;
  - 72 `DEPENDENCY` links;
  - 219 nodes with `PARENT_ID`;
  - zero broken link references to missing live nodes.
- The packaged legacy `repair-catalog-seed.json` still has empty `links`, and Liquibase alone is not enough to reconstruct the current operator graph. The HSQLDB snapshot is the source for the current mock seed.

Implementation:

- `panel` seed now copies nodes and explicit links from `old_db/hsqldb/wmspanel.script`.
- Constructor link anchor metadata remains stored in `RepairEstimateCatalogLink.comment` using `__anchors__:SOURCE->TARGET`.
- The React mock no longer derives visible `FOLLOW_UP` links from `parentId`; parent hierarchy remains separate node metadata for category membership and forms.
- The estimate catalog localStorage key moved to `rwms:repair-estimate-catalog:v2` so old `v1` mock states do not mask the database-backed seed.

Verification:

- `npm run typecheck` passed in `panel`.
- `npm run lint` passed in `panel`.
- `npm run build` passed in `panel`; only the existing Vite chunk-size warning was emitted.
- Browser validation on `/settings/estimates-repairs` confirmed the constructor header shows `232 узлов` and `254 связей`.
- Browser validation in the `Двери` constructor category confirmed 46 records, 56 rendered link hit paths, and no `seed-parent-link:*` generated links in the DOM.

## 2026-07-09 Catalog Code And Comment Presentation

Request:

- Hide catalog codes wherever they are not needed by an operator.
- Limit catalog node comments to 180 characters, with an in-place warning and destructive field state for invalid content.
- Show full comments in estimate catalog blocks and allow the blocks to grow vertically.

Implementation:

- `panel/src/features/settings/estimates-repairs/estimates-repairs-settings-page.tsx`
  - removes the editable `Код` field and code-only dialog descriptions;
  - keeps an operator-facing node name as the dialog description;
  - shows `N из 180 символов` under the comment field;
  - applies shadcn `Field data-invalid` and `Textarea aria-invalid` when the comment is too long, displays a warning, and disables save until it is corrected;
  - removes the canvas `line-clamp-2` restriction and measures card heights so SVG anchors and canvas bounds use the rendered height;
  - renders complete comments in catalog section tables instead of truncating them.
- `panel/src/features/settings/estimates-repairs/api/repair-estimate-catalog-store.ts`
  - rejects node comments longer than 180 characters as an adapter-level guard;
  - preserves existing technical codes and generates a unique `SYSTEM_<type>_<id>` code for newly created nodes when the UI supplies no code.

Migration impact:

- Technical catalog codes remain in the mock model for legacy compatibility, but are no longer an operator-editable or operator-visible field in the current catalog UI.
- The generated mock code scheme is target UI scaffolding only; the final Spring API code-generation rule remains `UNKNOWN`.

Verification:

- `npm run typecheck`, `npm run lint`, and `npm run build` passed in `panel`; Vite emitted only the existing chunk-size warning.
- Browser validation on `http://127.0.0.1:8080/settings/estimates-repairs` confirmed:
  - the block editor shows no code label or technical code;
  - entering 181 characters sets `aria-invalid`, uses the red shadcn invalid treatment, displays the 180-character warning, and disables save;
  - 180 characters is valid and enables save;
  - constructor cards have `min-height: 208px` with no maximum height or fixed height, so full wrapped comments can expand the card.

## 2026-07-10 Catalog Section Membership Uses The Full Tree

Finding:

- The database-derived mock already contained the correct work/material/furniture parent relationships, but the settings section filter built its parent lookup from only root categories and leaf rows. Any record under an intermediate `SUBCATEGORY`, `OPTION`, or `LOCATION` could therefore be omitted.

Implementation:

- `RepairEstimateCatalogSectionDto` now carries the existing complete catalog `nodes` tree once, rather than a duplicate `items` collection.
- Section rows are derived from `nodes` by the requested section type/scope. Category membership uses that same complete tree to follow `parentId` to the selected root category.

Verification:

- Browser validation confirmed `Работы → Окна/Витражи` renders all 9 records and `Мебель` renders all 11 records from the existing mock.

## 2026-07-10 Catalog Mock Concurrency Review

Finding:

- Node/link save/delete and reset previously performed an unguarded `localStorage` read-modify-write. Two constructor/settings contexts could read the same state and silently overwrite one another.

Implementation:

- `repair-estimate-catalog-store.ts` keeps the `rwms:repair-estimate-catalog:v2` key and accepts the prior `{ nodes, links }` payload as revision `0`.
- New writes use a flat envelope with `service: "repair-estimate-catalog"`, `schemaVersion: 2`, a validated non-negative safe-integer `revision`, `nodes`, and `links`.
- All mutations share a module-level promise queue and the origin-scoped lock `rwms:repair-estimate-catalog:mutation` when `navigator.locks` is available.
- A mutation captures `expectedRevision` at invocation, re-reads state after acquiring the lock, performs CAS, then validates and commits without an intervening await. A successful commit increments revision once; a mismatch rejects with a conflict message.
- Reads no longer perform normalization writes, so state migration cannot occur outside the mutation lock.

Migration impact:

- The mock preserves the current database-derived v2 seed/data and keeps persistence inside the settings adapter boundary.
- A future repair-catalog service must replace the mock CAS with an explicit version/ETag precondition and conflict response; the localStorage key/envelope and Web Lock name are not backend contracts.

Verification:

- A focused runtime scenario loaded an old v2 payload, started two concurrent valid node saves, observed one commit plus one conflict, retried successfully, and preserved the original node count while revisions advanced from `0` to `1` to `2`.
- `npm run typecheck`, `npm run lint`, `npm run build`, and `git diff --check` passed as documented in agent history.
