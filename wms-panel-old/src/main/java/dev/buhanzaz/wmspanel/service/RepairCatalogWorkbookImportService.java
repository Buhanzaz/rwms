package dev.buhanzaz.wmspanel.service;

import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogNode;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogNodeType;
import io.jmix.core.DataManager;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class RepairCatalogWorkbookImportService {

    private final DataManager dataManager;
    private final DataFormatter formatter = new DataFormatter();

    public RepairCatalogWorkbookImportService(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    @Transactional
    public ImportResult importWorkbook(Path workbookPath) {
        List<WorkbookRow> parsedRows = parseWorkbook(workbookPath);
        if (parsedRows.isEmpty()) {
            return new ImportResult(0, 0, 0);
        }

        Map<String, RepairEstimateCatalogNode> existingByCode = loadByCode();
        Map<String, RepairEstimateCatalogNode> existingByTypeAndName = loadByTypeAndName();
        int createdCategories = 0;
        int createdItems = 0;
        int updatedItems = 0;

        int order = 10;
        for (WorkbookRow row : parsedRows) {
            RepairEstimateCatalogNode node = existingByCode.get(row.code());
            if (node == null) {
                node = existingByTypeAndName.get(typeNameKey(row.type(), row.name()));
            }
            boolean creating = node == null;
            if (creating) {
                node = dataManager.create(RepairEstimateCatalogNode.class);
                node.setCode(row.code());
            }
            node.setName(row.name());
            node.setNodeType(row.type());
            node.setParent(null);
            node.setActive(row.active());
            node.setIncludeInEstimate(row.includeInEstimate());
            node.setCommonItem(false);
            node.setUnit(blankToNull(row.unit()));
            node.setUnitPrice(row.unitPrice());
            node.setDefaultQuantity(row.defaultQuantity());
            node.setDurationMinutes(row.durationMinutes());
            node.setAdditionalOption(false);
            node.setShowInMainMenu(false);
            node.setMainMenuOrder(null);
            node.setMainMenuTitle(null);
            node.setWorkQueue(null);
            node.setRouteQueueKind(null);
            node.setSortOrder(order);
            node.setComment("Импорт из Excel");
            node = dataManager.save(node);
            existingByCode.put(row.code(), node);
            if (creating) {
                createdItems++;
            } else {
                updatedItems++;
            }
            order += 10;
        }
        return new ImportResult(createdCategories, createdItems, updatedItems);
    }

    private List<WorkbookRow> parseWorkbook(Path workbookPath) {
        List<WorkbookRow> result = new ArrayList<>();
        try (InputStream inputStream = Files.newInputStream(workbookPath);
             Workbook workbook = WorkbookFactory.create(inputStream)) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                String currentCategory = null;
                boolean headerSeen = false;
                for (Row row : sheet) {
                    String first = cell(row, 0);
                    if (isCategoryRow(row)) {
                        currentCategory = first;
                        headerSeen = false;
                        continue;
                    }
                    if ("Тип".equalsIgnoreCase(first)) {
                        headerSeen = true;
                        continue;
                    }
                    if (!headerSeen || currentCategory == null) {
                        continue;
                    }
                    if (!"WORK".equalsIgnoreCase(first) && !"MATERIAL".equalsIgnoreCase(first)) {
                        continue;
                    }
                    RepairEstimateCatalogNodeType type = "WORK".equalsIgnoreCase(first)
                            ? RepairEstimateCatalogNodeType.WORK
                            : RepairEstimateCatalogNodeType.MATERIAL;
                    result.add(new WorkbookRow(
                            type,
                            normalizeCode(cell(row, 2)),
                            cell(row, 1),
                            yesNo(cell(row, 3)),
                            cell(row, 4),
                            decimal(cell(row, 5)),
                            integer(cell(row, 6), 1),
                            type == RepairEstimateCatalogNodeType.WORK ? 60 : null,
                            type == RepairEstimateCatalogNodeType.WORK ? yesNo(cell(row, 8)) : yesNo(cell(row, 7))
                    ));
                }
            }
            return result;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to import workbook: " + workbookPath, ex);
        }
    }

    private Map<String, RepairEstimateCatalogNode> loadByCode() {
        Map<String, RepairEstimateCatalogNode> result = new LinkedHashMap<>();
        for (RepairEstimateCatalogNode node : dataManager.load(RepairEstimateCatalogNode.class)
                .query("select e from RepairEstimateCatalogNode e")
                .list()) {
            if (node.getCode() != null && !node.getCode().isBlank()) {
                result.put(normalizeCode(node.getCode()), node);
            }
        }
        return result;
    }

    private Map<String, RepairEstimateCatalogNode> loadByTypeAndName() {
        Map<String, RepairEstimateCatalogNode> result = new LinkedHashMap<>();
        for (RepairEstimateCatalogNode node : dataManager.load(RepairEstimateCatalogNode.class)
                .query("select e from RepairEstimateCatalogNode e")
                .list()) {
            String key = typeNameKey(node.getNodeType(), node.getName());
            if (key != null) {
                result.putIfAbsent(key, node);
            }
        }
        return result;
    }

    private boolean isCategoryRow(Row row) {
        String first = cell(row, 0);
        if (first.isBlank() || "Тип".equalsIgnoreCase(first) || "WORK".equalsIgnoreCase(first) || "MATERIAL".equalsIgnoreCase(first)) {
            return false;
        }
        for (int i = 1; i < Math.max(9, row.getLastCellNum()); i++) {
            if (!cell(row, i).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private String cell(Row row, int index) {
        Cell cell = row.getCell(index);
        if (cell == null) {
            return "";
        }
        return formatter.formatCellValue(cell).trim();
    }

    private boolean yesNo(String value) {
        return "ДА".equalsIgnoreCase(value) || "TRUE".equalsIgnoreCase(value);
    }

    private Integer integer(String value, Integer fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return new BigDecimal(value.replace(',', '.')).intValue();
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.replace(" ", "").replace(',', '.'));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String normalizeCode(String value) {
        if (value == null) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String typeNameKey(RepairEstimateCatalogNodeType type, String name) {
        if (type == null || name == null || name.isBlank()) {
            return null;
        }
        return type.name() + "|" + name.trim().toUpperCase(Locale.ROOT);
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    public record ImportResult(int createdCategories, int createdItems, int updatedItems) {
    }

    private record WorkbookRow(
            RepairEstimateCatalogNodeType type,
            String code,
            String name,
            boolean includeInEstimate,
            String unit,
            BigDecimal unitPrice,
            Integer defaultQuantity,
            Integer durationMinutes,
            boolean active
    ) {
    }
}
