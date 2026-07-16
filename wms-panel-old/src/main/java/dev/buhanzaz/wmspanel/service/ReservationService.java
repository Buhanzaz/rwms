package dev.buhanzaz.wmspanel.service;

import dev.buhanzaz.wmspanel.entity.AccessoryItem;
import dev.buhanzaz.wmspanel.entity.AccessoryStockBalance;
import dev.buhanzaz.wmspanel.entity.RentalAttributeValue;
import dev.buhanzaz.wmspanel.entity.RentalCategory;
import dev.buhanzaz.wmspanel.entity.RentalItemCondition;
import dev.buhanzaz.wmspanel.entity.RentalItem;
import dev.buhanzaz.wmspanel.entity.RentalItemTag;
import dev.buhanzaz.wmspanel.entity.RentalTag;
import dev.buhanzaz.wmspanel.entity.RentalSubcategory;
import dev.buhanzaz.wmspanel.entity.RentalType;
import dev.buhanzaz.wmspanel.entity.Reservation;
import dev.buhanzaz.wmspanel.entity.ReservationAccessory;
import dev.buhanzaz.wmspanel.entity.ReservationAccessoryStatus;
import dev.buhanzaz.wmspanel.entity.ReservationClientType;
import dev.buhanzaz.wmspanel.entity.ReservationItemStatus;
import dev.buhanzaz.wmspanel.entity.ReservationLine;
import dev.buhanzaz.wmspanel.entity.ReservationStatus;
import dev.buhanzaz.wmspanel.entity.ReservationType;
import dev.buhanzaz.wmspanel.entity.StockItem;
import dev.buhanzaz.wmspanel.entity.User;
import dev.buhanzaz.wmspanel.entity.UserGlobalRole;
import dev.buhanzaz.wmspanel.entity.Warehouse;
import dev.buhanzaz.wmspanel.service.smartsearch.SmartReservationAttributeCriterion;
import io.jmix.core.DataManager;
import io.jmix.core.SaveContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.stream.Collectors;

@Service
public class ReservationService {
    private static final String BLOCKING_RENTAL_RESERVATION_LINE_CONDITION = """
            (l.status is null or l.status = 'ACTIVE' or l.status = 'RESERVED')
              and (
                  l.reservation.status is null
                  or l.reservation.status = 'ACTIVE'
                  or l.reservation.status = 'CONFIRMED'
                  or (
                      l.reservation.status = 'TEMPORARY'
                      and (l.reservation.temporaryExpiresAt is null or l.reservation.temporaryExpiresAt > :now)
                  )
                  or (
                      l.reservation.status = 'WAITING_PAYMENT'
                      and (l.reservation.paymentDueAt is null or l.reservation.paymentDueAt > :now)
                  )
              )
            """;

    private final DataManager dataManager;
    private final WarehouseAccessService warehouseAccessService;
    private final RentalItemService rentalItemService;
    private final ObjectProvider<ReservationExpirationService> reservationExpirationServiceProvider;
    @PersistenceContext
    private EntityManager entityManager;

    public ReservationService(DataManager dataManager,
                              WarehouseAccessService warehouseAccessService,
                              RentalItemService rentalItemService,
                              ObjectProvider<ReservationExpirationService> reservationExpirationServiceProvider) {
        this.dataManager = dataManager;
        this.warehouseAccessService = warehouseAccessService;
        this.rentalItemService = rentalItemService;
        this.reservationExpirationServiceProvider = reservationExpirationServiceProvider;
    }

    public List<Warehouse> reservationWarehouses() {
        User user = currentUser();
        if (user == null) {
            return List.of();
        }
        if (isSystemOrWmsAdmin(user)) {
            return loadActiveWarehouses();
        }
        return warehouseAccessService.getAvailableWarehousesForUser(user);
    }

    public int available(RentalItem item) {
        if (item == null) {
            return 0;
        }
        return activeReservedQuantity(item) > 0 ? 0 : 1;
    }

    public int available(StockItem item) {
        if (item == null || item.getTotalQuantity() == null) {
            return 0;
        }
        return Math.max(0, item.getTotalQuantity() - activeReservedQuantity(item));
    }

    public int activeReservedQuantity(RentalItem item) {
        return countBlockingRentalReservationLines(item, OffsetDateTime.now());
    }

    public int activeReservedQuantity(StockItem item) {
        OffsetDateTime now = OffsetDateTime.now();
        Long result = dataManager.loadValue("""
                        select coalesce(sum(l.quantity), 0)
                        from ReservationLine l
                        where l.stockItem = :item
                          and (l.status is null or l.status = 'ACTIVE' or l.status = 'RESERVED')
                          and (
                              l.reservation.status is null
                              or (
                                  l.reservation.status <> 'CANCELLED'
                                  and l.reservation.status <> 'EXPIRED'
                                  and l.reservation.status <> 'COMPLETED'
                              )
                          )
                          and (
                              (
                                  (l.reservation.status is null or l.reservation.status = 'ACTIVE' or l.reservation.status = 'TEMPORARY')
                                  and (l.reservation.temporaryExpiresAt is null or l.reservation.temporaryExpiresAt > :now)
                              )
                              or (
                                  l.reservation.status is not null
                                  and l.reservation.status <> 'ACTIVE'
                                  and l.reservation.status <> 'TEMPORARY'
                              )
                          )
                          and (
                              l.reservation.status is null
                              or l.reservation.status <> 'WAITING_PAYMENT'
                              or l.reservation.paymentDueAt is null
                              or l.reservation.paymentDueAt > :now
                          )
                        """, Long.class)
                .parameter("item", item)
                .parameter("now", now)
                .one();
        return result.intValue();
    }

    @Transactional(readOnly = true)
    public ReservationSearchResult searchAvailableRentalItems(ReservationSearchCriteria criteria) {
        ReservationSearchCriteria safeCriteria = criteria == null
                ? new ReservationSearchCriteria(null, null, null, null, null, 1, null, null, List.of(), List.of())
                : criteria;
        List<Warehouse> allowedWarehouses = reservationWarehouses();
        if (allowedWarehouses.isEmpty()) {
            return new ReservationSearchResult(List.of(), "Нет доступных складов для поиска", requestedQuantity(safeCriteria));
        }
        if (safeCriteria.warehouse() == null
                && (safeCriteria.warehouseQuery() == null || safeCriteria.warehouseQuery().isBlank())) {
            return new ReservationSearchResult(List.of(), "Выберите склад и нажмите Поиск", requestedQuantity(safeCriteria));
        }

        Map<String, Object> queryParameters = new LinkedHashMap<>();
        OffsetDateTime now = OffsetDateTime.now();
        StringBuilder query = new StringBuilder("""
                select e
                from RentalItem e
                where e.warehouse in :warehouses
                  and not exists (
                      select l.id
                      from ReservationLine l
                      where l.rentalItem = e
                        and """ + BLOCKING_RENTAL_RESERVATION_LINE_CONDITION + """
                  )
                """);
        queryParameters.put("warehouses", allowedWarehouses);
        queryParameters.put("now", now);

        if (safeCriteria.warehouse() != null && safeCriteria.warehouse().getId() != null) {
            query.append(" and e.warehouse = :warehouse");
            queryParameters.put("warehouse", safeCriteria.warehouse());
        }
        if (safeCriteria.category() != null && safeCriteria.category().getId() != null) {
            query.append(" and e.category = :category");
            queryParameters.put("category", safeCriteria.category());
        }
        if (safeCriteria.rentalClass() != null && safeCriteria.rentalClass().getId() != null) {
            query.append(" and e.subcategory = :subcategory");
            queryParameters.put("subcategory", safeCriteria.rentalClass());
        }
        if (safeCriteria.type() != null && safeCriteria.type().getId() != null) {
            query.append(" and e.type = :type");
            queryParameters.put("type", safeCriteria.type());
        }
        query.append(" order by coalesce(e.warehouse.sortOrder, 999999), e.warehouse.name, e.number");

        List<RentalItem> candidates = dataManager.load(RentalItem.class)
                .query(query.toString())
                .parameters(queryParameters)
                .fetchPlan(builder -> builder.addFetchPlan("_base")
                        .add("warehouse", "_base")
                        .add("category", "_base")
                        .add("subcategory", "_base")
                        .add("type", "_base")
                        .add("condition", "_base"))
                .list();

        String normalizedWarehouseQuery = normalize(safeCriteria.warehouseQuery());
        String warehouseQuery = normalizedWarehouseQuery == null
                ? ""
                : normalizedWarehouseQuery.toLowerCase(Locale.ROOT);

        String normalizedText = normalize(safeCriteria.text());
        String text = normalizedText == null
                ? ""
                : normalizedText.toLowerCase(Locale.ROOT);

        boolean hasDetailedCriteria = (safeCriteria.condition() != null)
                || (safeCriteria.attributeCriteria() != null && !safeCriteria.attributeCriteria().isEmpty())
                || (safeCriteria.tags() != null && !safeCriteria.tags().isEmpty())
                || !text.isBlank();

        Map<UUID, List<RentalAttributeValue>> attributeValuesByItemId = Map.of();
        Map<UUID, List<RentalTag>> tagsByItemId = Map.of();
        if (hasDetailedCriteria) {
            List<UUID> candidateIds = candidates.stream()
                    .map(RentalItem::getId)
                    .filter(Objects::nonNull)
                    .toList();
            attributeValuesByItemId = rentalItemService.loadAttributeValues(candidateIds).stream()
                    .filter(value -> value.getRentalItem() != null && value.getRentalItem().getId() != null)
                    .collect(Collectors.groupingBy(value -> value.getRentalItem().getId(), LinkedHashMap::new, Collectors.toList()));
            tagsByItemId = rentalItemService.loadItemTags(candidateIds).stream()
                    .filter(itemTag -> itemTag.getRentalItem() != null && itemTag.getRentalItem().getId() != null)
                    .filter(itemTag -> itemTag.getTag() != null)
                    .collect(Collectors.groupingBy(itemTag -> itemTag.getRentalItem().getId(),
                            LinkedHashMap::new,
                            Collectors.mapping(RentalItemTag::getTag, Collectors.toList())));
        } else {
            attributeValuesByItemId = Map.of();
            tagsByItemId = Map.of();
        }

        final Map<UUID, List<RentalAttributeValue>> finalAttributeValuesByItemId = attributeValuesByItemId;
        final Map<UUID, List<RentalTag>> finalTagsByItemId = tagsByItemId;

        List<RentalItem> filtered = candidates.stream()
                .filter(item -> safeCriteria.warehouse() == null || sameWarehouse(item.getWarehouse(), safeCriteria.warehouse()))
                .filter(item -> warehouseQuery.isBlank() || matchesWarehouseQuery(item.getWarehouse(), warehouseQuery))
                .filter(item -> safeCriteria.category() == null || sameWarehouseId(item.getCategory(), safeCriteria.category()))
                .filter(item -> safeCriteria.rentalClass() == null || sameWarehouseId(item.getSubcategory(), safeCriteria.rentalClass()))
                .filter(item -> safeCriteria.type() == null || sameWarehouseId(item.getType(), safeCriteria.type()))
                .filter(item -> safeCriteria.condition() == null || sameWarehouseId(item.getCondition(), safeCriteria.condition()))
                .filter(item -> isReservableStatus(item.getStatus()))
                .filter(item -> available(item) > 0)
                .filter(item -> safeCriteria.attributeCriteria() == null || safeCriteria.attributeCriteria().isEmpty()
                        || matchesAttributeCriteria(item, finalAttributeValuesByItemId.getOrDefault(item.getId(), List.of()), safeCriteria.attributeCriteria()))
                .filter(item -> safeCriteria.tags() == null || safeCriteria.tags().isEmpty()
                        || matchesTags(item, finalTagsByItemId.getOrDefault(item.getId(), List.of()), safeCriteria.tags()))
                .filter(item -> text.isBlank() || matchesText(item, text, finalAttributeValuesByItemId.getOrDefault(item.getId(), List.of()), finalTagsByItemId.getOrDefault(item.getId(), List.of())))
                .sorted(Comparator
                        .comparing((RentalItem item) -> item.getWarehouse() == null ? "" : item.getWarehouse().getName(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(RentalItem::getNumber, String.CASE_INSENSITIVE_ORDER))
                .toList();
        filtered.forEach(this::touchRentalItem);

        int requestedQuantity = requestedQuantity(safeCriteria);
        int matchedCount = filtered.size();
        String warning = matchedCount < requestedQuantity
                ? "Найдено только " + matchedCount + " из " + requestedQuantity + " доступных объектов"
                : null;
        List<RentalItem> limited = matchedCount <= requestedQuantity
                ? filtered
                : filtered.subList(0, requestedQuantity);
        return new ReservationSearchResult(limited, warning, requestedQuantity);
    }

    @Transactional
    public Reservation createTemporaryReservation(TemporaryReservationCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Не задано содержимое резерва");
        }
        Warehouse warehouse = command.warehouse();
        ensureReservableWarehouse(warehouse);

        List<RentalItem> rentalItems = normalizeRentalItems(command.rentalItems());
        if (rentalItems.isEmpty()) {
            throw new IllegalArgumentException("Выберите хотя бы один объект");
        }
        ensureSingleWarehouse(rentalItems, warehouse);
        rentalItems = lockRentalItems(rentalItems);
        rentalItems.forEach(item -> {
            ensureRentalItemCanBeReserved(item);
        });

        Reservation reservation = dataManager.create(Reservation.class);
        reservation.setReservationNumber(generateReservationNumber());
        reservation.setWarehouse(warehouse);
        reservation.setResponsibleRentalManager(command.responsibleRentalManager() != null
                ? command.responsibleRentalManager()
                : currentUser());
        reservation.setClientType(command.clientType() == null ? ReservationClientType.INDIVIDUAL : command.clientType());
        reservation.setIndividualLastName(normalize(command.individualLastName()));
        reservation.setIndividualFirstName(normalize(command.individualFirstName()));
        reservation.setIndividualMiddleName(normalize(command.individualMiddleName()));
        reservation.setCompanyName(normalize(command.companyName()));
        reservation.setLegalEntity(reservation.getCompanyName());
        reservation.setContactPerson(buildContactPerson(reservation));
        reservation.setReservationType(ReservationType.TEMPORARY);
        reservation.setStatus(ReservationStatus.TEMPORARY);
        reservation.setReservedBy(warehouseAccessService.username());
        reservation.setReservedAt(OffsetDateTime.now());
        reservation.setTemporaryExpiresAt(command.temporaryExpiresAt() == null
                ? OffsetDateTime.now().plusHours(24)
                : command.temporaryExpiresAt());
        reservation.setComment(normalize(command.comment()));
        reservation.setRentalItem(rentalItems.get(0));
        reservation.setReservationItems(new ArrayList<>());

        List<ReservationLine> lines = new ArrayList<>();
        for (RentalItem item : rentalItems) {
            ReservationLine line = dataManager.create(ReservationLine.class);
            line.setReservation(reservation);
            line.setRentalItem(item);
            line.setQuantity(1);
            line.setStatus(ReservationItemStatus.ACTIVE);
            line.setComment(null);
            item.setStatus("TEMP_RESERVED");
            lines.add(line);
        }
        reservation.getReservationItems().addAll(lines);

        SaveContext saveContext = new SaveContext();
        saveContext.saving(reservation);
        lines.forEach(saveContext::saving);
        rentalItems.forEach(saveContext::saving);
        dataManager.save(saveContext);
        return reservation;
    }

    @Transactional
    public ReservationAccessory addAccessoryToReservationItem(AddAccessoryCommand command) {
        if (command == null || command.reservationItem() == null || command.accessoryItem() == null) {
            throw new IllegalArgumentException("Не задана строка резерва или допоборудование");
        }
        ReservationLine reservationItem = dataManager.load(ReservationLine.class)
                .id(command.reservationItem().getId())
                .one();
        Reservation reservation = reservationItem.getReservation();
        ensureReservationCanBeModified(reservation);
        if (reservationItem.getStatus() != ReservationItemStatus.ACTIVE && reservationItem.getStatus() != ReservationItemStatus.RESERVED) {
            throw new IllegalArgumentException("Допоборудование можно добавить только к активной строке резерва");
        }
        Integer quantity = command.quantity() == null ? 0 : command.quantity();
        if (quantity <= 0) {
            throw new IllegalArgumentException("Количество допоборудования должно быть больше 0");
        }

        AccessoryStockBalance balance = dataManager.load(AccessoryStockBalance.class)
                .query("""
                        select e
                        from AccessoryStockBalance e
                        where e.warehouse = :warehouse
                          and e.accessoryItem = :accessoryItem
                        """)
                .parameter("warehouse", reservation.getWarehouse())
                .parameter("accessoryItem", command.accessoryItem())
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Остаток допоборудования для выбранного склада не найден"));

        if (balance.getQuantityAvailable() == null || balance.getQuantityAvailable() < quantity) {
            throw new IllegalArgumentException("На складе доступно только " + (balance.getQuantityAvailable() == null ? 0 : balance.getQuantityAvailable()) + " шт.");
        }

        balance.setQuantityAvailable(balance.getQuantityAvailable() - quantity);
        balance.setQuantityReserved(balance.getQuantityReserved() + quantity);

        ReservationAccessory accessory = dataManager.create(ReservationAccessory.class);
        accessory.setReservation(reservation);
        accessory.setReservationItem(reservationItem);
        accessory.setAccessoryItem(command.accessoryItem());
        accessory.setQuantity(quantity);
        accessory.setStatus(ReservationAccessoryStatus.ACTIVE);
        accessory.setComment(normalize(command.comment()));

        SaveContext saveContext = new SaveContext();
        saveContext.saving(balance, accessory);
        dataManager.save(saveContext);
        return accessory;
    }

    @Transactional
    public Reservation reserveWarehouseItem(RentalItem item, ReservationType type, OffsetDateTime expiresAt,
                                            String companyName, String legalEntity, String contactPerson, String comment,
                                            RentalItem linkedRentalItem) {
        warehouseAccessService.checkAccess(item.getWarehouse());
        validateLinkedRentalItem(item.getWarehouse(), linkedRentalItem);
        item = lockRentalItem(item);
        linkedRentalItem = lockRentalItem(linkedRentalItem);
        ensureRentalItemCanBeReserved(item);
        Reservation reservation = createLegacyReservation(item.getWarehouse(), type, expiresAt, companyName, legalEntity, contactPerson, comment, linkedRentalItem);
        ReservationLine line = dataManager.create(ReservationLine.class);
        line.setReservation(reservation);
        line.setRentalItem(item);
        line.setQuantity(1);
        line.setStatus(ReservationItemStatus.ACTIVE);
        item.setStatus(type == ReservationType.CLIENT ? "RESERVED" : "TEMP_RESERVED");

        SaveContext saveContext = new SaveContext();
        saveContext.saving(reservation, line, item);
        dataManager.save(saveContext);
        return reservation;
    }

    @Transactional
    public Reservation reserveClientPackage(RentalItem item,
                                            String companyName,
                                            String legalEntity,
                                            String comment,
                                            List<StockReservationRequest> stockRequests) {
        warehouseAccessService.checkAccess(item.getWarehouse());
        item = lockRentalItem(item);
        ensureRentalItemCanBeReserved(item);

        Reservation reservation = createLegacyReservation(
                item.getWarehouse(),
                ReservationType.CLIENT,
                null,
                companyName,
                legalEntity,
                null,
                comment,
                item);

        ReservationLine rentalLine = dataManager.create(ReservationLine.class);
        rentalLine.setReservation(reservation);
        rentalLine.setRentalItem(item);
        rentalLine.setQuantity(1);
        rentalLine.setStatus(ReservationItemStatus.ACTIVE);
        item.setStatus("RESERVED");

        SaveContext saveContext = new SaveContext();
        saveContext.saving(reservation, rentalLine, item);

        if (stockRequests != null) {
            for (StockReservationRequest request : stockRequests) {
                if (request == null || request.stockItem() == null || request.quantity() == null || request.quantity() <= 0) {
                    continue;
                }
                warehouseAccessService.checkAccess(request.stockItem().getWarehouse());
                int available = available(request.stockItem());
                if (request.quantity() > available) {
                    throw new IllegalArgumentException("На складе " + request.stockItem().getWarehouse().getName()
                            + " доступно только " + available + " " + request.stockItem().getUnit());
                }

                ReservationLine stockLine = dataManager.create(ReservationLine.class);
                stockLine.setReservation(reservation);
                stockLine.setStockItem(request.stockItem());
                stockLine.setQuantity(request.quantity());
                stockLine.setStatus(ReservationItemStatus.ACTIVE);
                saveContext.saving(stockLine);
            }
        }

        dataManager.save(saveContext);
        return reservation;
    }

    @Transactional
    public Reservation reserveStockItem(StockItem item, int quantity, ReservationType type, OffsetDateTime expiresAt,
                                        String companyName, String legalEntity, String contactPerson, String comment,
                                        RentalItem linkedRentalItem) {
        warehouseAccessService.checkAccess(item.getWarehouse());
        validateLinkedRentalItem(item.getWarehouse(), linkedRentalItem);
        int available = available(item);
        if (quantity <= 0) {
            throw new IllegalArgumentException("Количество резерва должно быть больше 0");
        }
        if (quantity > available) {
            throw new IllegalArgumentException("На складе " + item.getWarehouse().getName() + " доступно только " + available + " " + item.getUnit());
        }
        Reservation reservation = createLegacyReservation(item.getWarehouse(), type, expiresAt, companyName, legalEntity, contactPerson, comment, linkedRentalItem);
        ReservationLine line = dataManager.create(ReservationLine.class);
        line.setReservation(reservation);
        line.setStockItem(item);
        line.setQuantity(quantity);
        line.setStatus(ReservationItemStatus.ACTIVE);

        SaveContext saveContext = new SaveContext();
        saveContext.saving(reservation, line);
        dataManager.save(saveContext);
        return reservation;
    }

    private List<RentalItem> lockRentalItems(List<RentalItem> rentalItems) {
        if (rentalItems == null || rentalItems.isEmpty()) {
            return List.of();
        }
        List<RentalItem> lockedItems = new ArrayList<>();
        for (RentalItem rentalItem : rentalItems) {
            RentalItem locked = lockRentalItem(rentalItem);
            if (locked != null) {
                lockedItems.add(locked);
            }
        }
        return lockedItems;
    }

    private RentalItem lockRentalItem(RentalItem rentalItem) {
        if (rentalItem == null || rentalItem.getId() == null || entityManager == null) {
            return rentalItem;
        }
        return entityManager.find(RentalItem.class, rentalItem.getId(), LockModeType.PESSIMISTIC_WRITE);
    }

    private void ensureRentalItemCanBeReserved(RentalItem item) {
        if (item == null) {
            throw new IllegalArgumentException("Бытовка не найдена");
        }
        if (!isReservableStatus(item.getStatus())) {
            throw new IllegalArgumentException("Бытовка " + item.getNumber() + " не доступна для резерва");
        }
        if (hasBlockingRentalReservationLine(item, OffsetDateTime.now())) {
            throw new IllegalArgumentException("Бытовка " + item.getNumber() + " уже забронирована другим пользователем. Обновите поиск.");
        }
    }

    private int countBlockingRentalReservationLines(RentalItem item, OffsetDateTime now) {
        if (item == null || item.getId() == null) {
            return 0;
        }
        Long result = dataManager.loadValue("""
                        select coalesce(count(l), 0)
                        from ReservationLine l
                        where l.rentalItem.id = :itemId
                          and """ + BLOCKING_RENTAL_RESERVATION_LINE_CONDITION, Long.class)
                .parameter("itemId", item.getId())
                .parameter("now", now)
                .one();
        return result.intValue();
    }

    private boolean hasBlockingRentalReservationLine(RentalItem item, OffsetDateTime now) {
        return countBlockingRentalReservationLines(item, now) > 0;
    }

    @Transactional
    public void expireTemporaryReservations() {
        ReservationExpirationService reservationExpirationService = reservationExpirationServiceProvider.getIfAvailable();
        if (reservationExpirationService != null) {
            reservationExpirationService.expireTemporaryReservations();
            return;
        }

        dataManager.load(Reservation.class)
                .query("""
                        select e
                        from Reservation e
                        where e.status in ('ACTIVE', 'TEMPORARY')
                          and e.reservationType = 'TEMPORARY'
                          and e.temporaryExpiresAt <= :now
                        """)
                .parameter("now", OffsetDateTime.now())
                .list()
                .forEach(reservation -> {
                    reservation.setStatus(ReservationStatus.EXPIRED);
                    dataManager.save(reservation);
                });
    }

    private Reservation createLegacyReservation(Warehouse warehouse, ReservationType type, OffsetDateTime expiresAt,
                                                String companyName, String legalEntity, String contactPerson, String comment,
                                                RentalItem linkedRentalItem) {
        Reservation reservation = dataManager.create(Reservation.class);
        reservation.setReservationNumber(generateReservationNumber());
        reservation.setWarehouse(warehouse);
        reservation.setRentalItem(linkedRentalItem);
        reservation.setReservationType(type);
        reservation.setStatus(ReservationStatus.ACTIVE);
        reservation.setClientType(inferClientType(companyName, legalEntity, contactPerson));
        reservation.setResponsibleRentalManager(currentUser());
        reservation.setCompanyName(normalize(companyName));
        reservation.setLegalEntity(normalize(legalEntity));
        reservation.setContactPerson(normalize(contactPerson));
        reservation.setReservedBy(warehouseAccessService.username());
        reservation.setReservedAt(OffsetDateTime.now());
        reservation.setTemporaryExpiresAt(expiresAt);
        reservation.setComment(normalize(comment));
        if (reservation.getClientType() == ReservationClientType.LEGAL_ENTITY && reservation.getCompanyName() != null) {
            reservation.setLegalEntity(reservation.getCompanyName());
        }
        return reservation;
    }

    private ReservationClientType inferClientType(String companyName, String legalEntity, String contactPerson) {
        if (isBlank(companyName) && isBlank(legalEntity) && !isBlank(contactPerson)) {
            return ReservationClientType.INDIVIDUAL;
        }
        if (!isBlank(companyName) || !isBlank(legalEntity)) {
            return ReservationClientType.LEGAL_ENTITY;
        }
        return ReservationClientType.INDIVIDUAL;
    }

    private String generateReservationNumber() {
        for (int i = 0; i < 8; i++) {
            String candidate = "R-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
                    + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
            if (!reservationNumberExists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Не удалось сгенерировать уникальный номер резерва");
    }

    private boolean reservationNumberExists(String candidate) {
        Long count = dataManager.loadValue("""
                        select count(e)
                        from Reservation e
                        where e.reservationNumber = :reservationNumber
                        """, Long.class)
                .parameter("reservationNumber", candidate)
                .one();
        return count != null && count > 0;
    }

    private List<RentalItem> normalizeRentalItems(List<RentalItem> rentalItems) {
        if (rentalItems == null || rentalItems.isEmpty()) {
            return List.of();
        }
        return rentalItems.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private void ensureSingleWarehouse(List<RentalItem> rentalItems, Warehouse expectedWarehouse) {
        for (RentalItem item : rentalItems) {
            if (item.getWarehouse() == null || expectedWarehouse == null || !Objects.equals(item.getWarehouse().getId(), expectedWarehouse.getId())) {
            throw new IllegalArgumentException("Все объекты в резерве должны быть из одного склада");
            }
            if (!canReserveWarehouse(item.getWarehouse())) {
                throw new IllegalArgumentException("Нет доступа к выбранному складу");
            }
        }
    }

    private void ensureReservableWarehouse(Warehouse warehouse) {
        if (!canReserveWarehouse(warehouse)) {
            throw new IllegalArgumentException("Нет доступа к выбранному складу");
        }
    }

    private void ensureReservationCanBeModified(Reservation reservation) {
        if (reservation == null) {
            throw new IllegalArgumentException("Резерв не найден");
        }
        ReservationStatus status = reservation.getStatus();
        if (status == ReservationStatus.CANCELLED || status == ReservationStatus.EXPIRED || status == ReservationStatus.COMPLETED) {
            throw new IllegalArgumentException("Резерв уже закрыт");
        }
        ensureReservableWarehouse(reservation.getWarehouse());
    }

    private void validateLinkedRentalItem(Warehouse warehouse, RentalItem linkedRentalItem) {
        if (linkedRentalItem == null) {
            return;
        }
        if (warehouse == null || linkedRentalItem.getWarehouse() == null
                || !Objects.equals(warehouse.getId(), linkedRentalItem.getWarehouse().getId())) {
            throw new IllegalArgumentException("Связанный объект должен быть из того же склада");
        }
        if (!canReserveWarehouse(linkedRentalItem.getWarehouse())) {
            throw new IllegalArgumentException("Нет доступа к выбранному складу");
        }
    }

    private boolean sameWarehouse(Warehouse left, Warehouse right) {
        return left != null && right != null && Objects.equals(left.getId(), right.getId());
    }

    private boolean sameWarehouseId(Object left, Object right) {
        if (left == null || right == null) {
            return false;
        }
        try {
            return Objects.equals(left.getClass().getMethod("getId").invoke(left), right.getClass().getMethod("getId").invoke(right));
        } catch (ReflectiveOperationException ex) {
            return false;
        }
    }

    private boolean matchesWarehouseQuery(Warehouse warehouse, String query) {
        if (warehouse == null || query == null || query.isBlank()) {
            return false;
        }
        return Stream.of(warehouse.getName(), warehouse.getCity(), warehouse.getCode())
                .filter(Objects::nonNull)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.contains(query));
    }

    private boolean matchesAttributeCriteria(RentalItem item,
                                             List<RentalAttributeValue> values,
                                             List<SmartReservationAttributeCriterion> criteria) {
        if (item == null || criteria == null || criteria.isEmpty()) {
            return true;
        }
        for (SmartReservationAttributeCriterion criterion : criteria) {
            if (criterion == null || criterion.definition() == null || criterion.definition().getId() == null) {
                continue;
            }
            boolean matched = false;
            for (RentalAttributeValue value : values) {
                if (value == null || value.getAttributeDefinition() == null || value.getAttributeDefinition().getId() == null) {
                    continue;
                }
                if (!Objects.equals(criterion.definition().getId(), value.getAttributeDefinition().getId())) {
                    continue;
                }
                if (matchesAttributeValue(value, criterion)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesAttributeValue(RentalAttributeValue value, SmartReservationAttributeCriterion criterion) {
        if (value == null || criterion == null) {
            return false;
        }
        if (!criterion.options().isEmpty()) {
            return criterion.options().stream()
                    .filter(Objects::nonNull)
                    .anyMatch(option -> optionMatches(value, option));
        }
        if (criterion.booleanValue() != null) {
            return Objects.equals(value.getValueBoolean(), criterion.booleanValue());
        }
        if (criterion.numberValue() != null) {
            Double number = value.getValueNumber();
            return number != null && Math.abs(number - criterion.numberValue()) < 0.0001d;
        }
        if (criterion.textValue() != null && !criterion.textValue().isBlank()) {
            return normalizedDisplayValue(value).contains(criterion.textValue().toLowerCase(Locale.ROOT));
        }
        return true;
    }

    private boolean optionMatches(RentalAttributeValue value, dev.buhanzaz.wmspanel.entity.RentalAttributeOption option) {
        if (value == null || option == null) {
            return false;
        }
        if (value.getValueOption() != null) {
            if (value.getValueOption().getId() != null && option.getId() != null && Objects.equals(value.getValueOption().getId(), option.getId())) {
                return true;
            }
            if (value.getValueOption().getCode() != null && option.getCode() != null && value.getValueOption().getCode().equalsIgnoreCase(option.getCode())) {
                return true;
            }
            if (value.getValueOption().getName() != null && option.getName() != null && value.getValueOption().getName().equalsIgnoreCase(option.getName())) {
                return true;
            }
        }
        String displayValue = normalizedDisplayValue(value);
        return option.getName() != null && displayValue.contains(option.getName().toLowerCase(Locale.ROOT));
    }

    private boolean matchesTags(RentalItem item, List<RentalTag> itemTags, List<RentalTag> requiredTags) {
        if (item == null || requiredTags == null || requiredTags.isEmpty()) {
            return true;
        }
        Map<UUID, RentalTag> tagsById = itemTags == null ? Map.of() : itemTags.stream()
                .filter(Objects::nonNull)
                .filter(tag -> tag.getId() != null)
                .collect(Collectors.toMap(RentalTag::getId, tag -> tag, (left, right) -> left, LinkedHashMap::new));
        for (RentalTag requiredTag : requiredTags) {
            if (requiredTag == null || requiredTag.getId() == null) {
                continue;
            }
            if (!tagsById.containsKey(requiredTag.getId())) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesText(RentalItem item, String text, List<RentalAttributeValue> attributeValues, List<RentalTag> tags) {
        if (item == null || text == null || text.isBlank()) {
            return false;
        }
        Stream<String> baseValues = Stream.of(
                        item.getNumber(),
                        item.getStatus(),
                        item.getComment(),
                        item.getWarehouse() == null ? null : item.getWarehouse().getName(),
                        item.getWarehouse() == null ? null : item.getWarehouse().getCity(),
                        item.getWarehouse() == null ? null : item.getWarehouse().getCode(),
                        item.getCategory() == null ? null : item.getCategory().getName(),
                        item.getSubcategory() == null ? null : item.getSubcategory().getName(),
                        item.getType() == null ? null : item.getType().getName(),
                        item.getCondition() == null ? null : item.getCondition().getName(),
                        item.getCondition() == null ? null : item.getCondition().getCode())
                .filter(Objects::nonNull);

        Stream<String> attributeTexts = attributeValues == null ? Stream.empty() : attributeValues.stream()
                .filter(Objects::nonNull)
                .flatMap(value -> Stream.of(
                        value.getDisplayName(),
                        value.getValueString(),
                        value.getValueText(),
                        value.getValueOption() == null ? null : value.getValueOption().getName(),
                        value.getValueOption() == null ? null : value.getValueOption().getCode(),
                        value.getAttributeDefinition() == null ? null : value.getAttributeDefinition().getName(),
                        value.getAttributeDefinition() == null ? null : value.getAttributeDefinition().getCode())
                        .filter(Objects::nonNull));

        Stream<String> tagTexts = tags == null ? Stream.empty() : tags.stream()
                .filter(Objects::nonNull)
                .flatMap(tag -> Stream.of(tag.getName(), tag.getCode()).filter(Objects::nonNull));

        return Stream.concat(Stream.concat(baseValues, attributeTexts), tagTexts)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.contains(text));
    }

    private String normalizedDisplayValue(RentalAttributeValue value) {
        if (value == null) {
            return "";
        }
        String displayName = value.getDisplayName();
        if (displayName != null && !displayName.isBlank()) {
            return displayName.toLowerCase(Locale.ROOT);
        }
        return Stream.of(
                        value.getValueString(),
                        value.getValueText(),
                        value.getValueOption() == null ? null : value.getValueOption().getName(),
                        value.getValueOption() == null ? null : value.getValueOption().getCode())
                .filter(Objects::nonNull)
                .map(valueText -> valueText.toLowerCase(Locale.ROOT))
                .reduce((left, right) -> left + " " + right)
                .orElse("");
    }

    private boolean isReservableStatus(String status) {
        return status != null && (
                "READY".equalsIgnoreCase(status)
                        || "AVAILABLE".equalsIgnoreCase(status)
                        || "Свободная".equalsIgnoreCase(status)
                        || "Доступная".equalsIgnoreCase(status)
                        || "Доступно".equalsIgnoreCase(status));
    }

    private int requestedQuantity(ReservationSearchCriteria criteria) {
        if (criteria == null || criteria.quantity() == null || criteria.quantity() <= 0) {
            return 1;
        }
        return criteria.quantity();
    }

    private String buildContactPerson(Reservation reservation) {
        if (reservation == null) {
            return null;
        }
        if (reservation.getClientType() == ReservationClientType.LEGAL_ENTITY) {
            return normalize(reservation.getCompanyName());
        }
        return Stream.of(reservation.getIndividualLastName(), reservation.getIndividualFirstName(), reservation.getIndividualMiddleName())
                .filter(value -> value != null && !value.isBlank())
                .reduce((left, right) -> left + " " + right)
                .orElse(null);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public boolean canReserveWarehouse(Warehouse warehouse) {
        return canReserveWarehouse(currentUser(), warehouse);
    }

    public boolean canManageReservation(Warehouse warehouse) {
        return canReserveWarehouse(warehouse);
    }

    public boolean canManageReservation(Reservation reservation) {
        if (reservation == null) {
            return false;
        }
        User user = currentUser();
        if (user == null) {
            return false;
        }
        if (isSystemOrWmsAdmin(user)) {
            return true;
        }
        if (user.getGlobalRole() == UserGlobalRole.RENTAL_MANAGER) {
            return sameUser(user, reservation.getResponsibleRentalManager())
                    && canReserveWarehouse(reservation.getWarehouse());
        }
        return canReserveWarehouse(reservation.getWarehouse());
    }

    private boolean canReserveWarehouse(User user, Warehouse warehouse) {
        if (warehouse == null || user == null) {
            return false;
        }
        if (isSystemOrWmsAdmin(user)) {
            return true;
        }
        return warehouseAccessService.canEditWarehouseItems(user, warehouse);
    }

    private boolean isSystemOrWmsAdmin(User user) {
        return warehouseAccessService.isSystemOrWmsAdmin(user);
    }

    private boolean sameUser(User left, User right) {
        return left != null && right != null && left.getId() != null && right.getId() != null && Objects.equals(left.getId(), right.getId());
    }

    public User currentUser() {
        String username = warehouseAccessService.username();
        if (username == null || username.isBlank()) {
            return null;
        }
        return dataManager.load(User.class)
                .query("select e from wmspanel_User e where e.username = :username")
                .parameter("username", username)
                .optional()
                .orElse(null);
    }

    private List<Warehouse> loadActiveWarehouses() {
        return dataManager.load(Warehouse.class)
                .query("select e from Warehouse e where e.active = true order by coalesce(e.sortOrder, 999999), e.name")
                .list();
    }

    private void touchRentalItem(RentalItem item) {
        if (item == null) {
            return;
        }
        if (item.getWarehouse() != null) {
            item.getWarehouse().getName();
            item.getWarehouse().getCity();
            item.getWarehouse().getCode();
        }
        if (item.getCategory() != null) {
            item.getCategory().getName();
        }
        if (item.getSubcategory() != null) {
            item.getSubcategory().getName();
        }
        if (item.getType() != null) {
            item.getType().getName();
        }
        if (item.getCondition() != null) {
            item.getCondition().getName();
            item.getCondition().getCode();
        }
    }

    public record ReservationSearchCriteria(
            Warehouse warehouse,
            String warehouseQuery,
            RentalCategory category,
            RentalSubcategory rentalClass,
            RentalType type,
            Integer quantity,
            String text,
            RentalItemCondition condition,
            List<SmartReservationAttributeCriterion> attributeCriteria,
            List<RentalTag> tags) {

        public RentalSubcategory subcategory() {
            return rentalClass;
        }
    }

    public record ReservationSearchResult(List<RentalItem> items, String warning, int requestedQuantity) {
    }

    public record TemporaryReservationCommand(
            Warehouse warehouse,
            ReservationClientType clientType,
            String individualLastName,
            String individualFirstName,
            String individualMiddleName,
            String companyName,
            User responsibleRentalManager,
            OffsetDateTime temporaryExpiresAt,
            String comment,
            List<RentalItem> rentalItems) {
    }

    public record AddAccessoryCommand(
            ReservationLine reservationItem,
            AccessoryItem accessoryItem,
            Integer quantity,
            String comment) {
    }

    public record StockReservationRequest(StockItem stockItem, Integer quantity) {
    }
}
