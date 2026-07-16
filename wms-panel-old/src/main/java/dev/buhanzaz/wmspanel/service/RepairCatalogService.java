package dev.buhanzaz.wmspanel.service;

import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogLink;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogLinkType;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogNode;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogNodeType;
import io.jmix.core.DataManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class RepairCatalogService {

    private static final long CACHE_TTL_MILLIS = 3000L;
    private final DataManager dataManager;
    private volatile CatalogSnapshot cachedSnapshot;
    private volatile long cachedAtMillis;

    public RepairCatalogService() {
        this(null);
    }

    @Autowired
    public RepairCatalogService(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    public List<CatalogNode> rootNodes() {
        return currentSnapshot().rootNodes();
    }

    public List<CatalogNode> mainMenuNodes() {
        return currentSnapshot().mainMenuNodes();
    }

    public List<CatalogNode> children(String code) {
        CatalogSnapshot snapshot = currentSnapshot();
        return snapshot.childrenByParent().getOrDefault(normalizeCode(code), List.of()).stream()
                .map(snapshot.nodes()::get)
                .filter(Objects::nonNull)
                .toList();
    }

    public List<CatalogNode> dependencyNodes(String code) {
        return linkedNodes(currentSnapshot().dependencyLinksBySource(), code);
    }

    public List<CatalogNode> dependencyRelatedNodes(String code) {
        CatalogSnapshot snapshot = currentSnapshot();
        String normalizedCode = normalizeCode(code);
        if (normalizedCode == null) {
            return List.of();
        }
        Set<String> relatedCodes = new LinkedHashSet<>(
                snapshot.dependencyLinksBySource().getOrDefault(normalizedCode, List.of()));
        for (Map.Entry<String, List<String>> entry : snapshot.dependencyLinksBySource().entrySet()) {
            if (entry.getValue().contains(normalizedCode)) {
                relatedCodes.add(entry.getKey());
            }
        }
        return relatedCodes.stream()
                .map(snapshot.nodes()::get)
                .filter(Objects::nonNull)
                .toList();
    }

    public List<CatalogNode> followUpNodes(String code) {
        return linkedNodes(currentSnapshot().followUpLinksBySource(), code);
    }

    public CatalogNode get(String code) {
        return currentSnapshot().nodes().get(normalizeCode(code));
    }

    public boolean isFurnitureCode(String code) {
        String normalized = normalizeCode(code);
        if (normalized == null) {
            return false;
        }
        CatalogSnapshot snapshot = currentSnapshot();
        CatalogNode node = snapshot.nodes().get(normalized);
        if (node != null && node.furnitureCategory()) {
            return true;
        }
        while (node != null) {
            if (node.furnitureCategory()) {
                return true;
            }
            String parentCode = snapshot.parentByChild().get(node.code());
            if (parentCode == null) {
                break;
            }
            node = snapshot.nodes().get(parentCode);
        }
        return false;
    }

    public Collection<CatalogNode> allNodes() {
        return Collections.unmodifiableCollection(currentSnapshot().nodes().values());
    }

    CatalogSnapshot buildSnapshot(List<RepairEstimateCatalogNode> databaseNodes,
                                  List<RepairEstimateCatalogLink> databaseLinks) {
        Map<String, CatalogNode> nodes = new LinkedHashMap<>();
        Map<String, List<String>> childrenByParent = new LinkedHashMap<>();
        Map<String, List<String>> dependencyLinksBySource = new LinkedHashMap<>();
        Map<String, List<String>> followUpLinksBySource = new LinkedHashMap<>();
        Map<String, String> parentByChild = new LinkedHashMap<>();
        Set<String> childCodes = new LinkedHashSet<>();
        List<MainMenuNode> mainMenuNodes = new ArrayList<>();

        for (RepairEstimateCatalogNode node : databaseNodes) {
            String code = normalizeCode(node.getCode());
            if (code == null) {
                continue;
            }
            CatalogNode catalogNode = new CatalogNode(
                    code,
                    mapNodeType(node),
                    node.getName(),
                    shortTitle(node),
                    node.getUnit(),
                    node.getUnitPrice(),
                    defaultQuantity(node),
                    Boolean.TRUE.equals(node.getIncludeInEstimate()),
                    Boolean.TRUE.equals(node.getCommonItem()),
                    Boolean.TRUE.equals(node.getFurnitureCategory()),
                    Boolean.TRUE.equals(node.getAdditionalOption()),
                    Boolean.TRUE.equals(node.getShowInMainMenu()),
                    node.getMainMenuOrder(),
                    blankToNull(node.getMainMenuTitle()));
            nodes.put(code, catalogNode);
            if (catalogNode.showInMainMenu()) {
                mainMenuNodes.add(new MainMenuNode(catalogNode, node.getMainMenuOrder()));
            }
        }

        for (RepairEstimateCatalogNode node : databaseNodes) {
            String code = normalizeCode(node.getCode());
            if (code == null || node.getParent() == null) {
                continue;
            }
            String parentCode = normalizeCode(node.getParent().getCode());
            if (!nodes.containsKey(parentCode) || !nodes.containsKey(code)) {
                continue;
            }
            childrenByParent.computeIfAbsent(parentCode, ignored -> new ArrayList<>()).add(code);
            parentByChild.put(code, parentCode);
            childCodes.add(code);
        }

        for (RepairEstimateCatalogLink link : databaseLinks) {
            if (link.getSourceNode() == null || link.getTargetNode() == null) {
                continue;
            }
            String sourceCode = normalizeCode(link.getSourceNode().getCode());
            String targetCode = normalizeCode(link.getTargetNode().getCode());
            if (!nodes.containsKey(sourceCode) || !nodes.containsKey(targetCode)) {
                continue;
            }
            RepairEstimateCatalogLinkType linkType = link.getLinkType();
            if (linkType == RepairEstimateCatalogLinkType.FOLLOW_UP) {
                addLinkedCode(followUpLinksBySource, sourceCode, targetCode);
                continue;
            }
            if (linkType == RepairEstimateCatalogLinkType.DEPENDENCY) {
                addLinkedCode(dependencyLinksBySource, sourceCode, targetCode);
                continue;
            }
        }

        List<CatalogNode> rootNodes = databaseNodes.stream()
                .filter(node -> node.getParent() == null)
                .filter(node -> node.getNodeType() == RepairEstimateCatalogNodeType.CATEGORY)
                .map(node -> nodes.get(normalizeCode(node.getCode())))
                .filter(Objects::nonNull)
                .toList();

        List<CatalogNode> resolvedMainMenuNodes = mainMenuNodes.stream()
                .sorted((left, right) -> compareNullable(left.order(), right.order(), left.node().title(), right.node().title()))
                .map(entry -> withMainMenuTitle(entry.node()))
                .toList();
        if (resolvedMainMenuNodes.isEmpty()) {
            resolvedMainMenuNodes = rootNodes;
        }

        return new CatalogSnapshot(
                nodes,
                childrenByParent,
                dependencyLinksBySource,
                followUpLinksBySource,
                parentByChild,
                childCodes,
                rootNodes,
                resolvedMainMenuNodes);
    }

    void replaceSnapshot(CatalogSnapshot snapshot) {
        cachedSnapshot = snapshot;
        cachedAtMillis = System.currentTimeMillis();
    }

    public void clearCache() {
        cachedSnapshot = null;
        cachedAtMillis = 0L;
    }

    private List<CatalogNode> linkedNodes(Map<String, List<String>> codesBySource, String code) {
        CatalogSnapshot snapshot = currentSnapshot();
        return codesBySource.getOrDefault(normalizeCode(code), List.of()).stream()
                .map(snapshot.nodes()::get)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private CatalogSnapshot currentSnapshot() {
        CatalogSnapshot snapshot = cachedSnapshot;
        long now = System.currentTimeMillis();
        if (snapshot != null && now - cachedAtMillis <= CACHE_TTL_MILLIS) {
            return snapshot;
        }
        synchronized (this) {
            snapshot = cachedSnapshot;
            now = System.currentTimeMillis();
            if (snapshot != null && now - cachedAtMillis <= CACHE_TTL_MILLIS) {
                return snapshot;
            }
            CatalogSnapshot rebuilt = loadDatabaseSnapshot();
            cachedSnapshot = rebuilt;
            cachedAtMillis = now;
            return rebuilt;
        }
    }

    private CatalogSnapshot loadDatabaseSnapshot() {
        if (dataManager == null) {
            return CatalogSnapshot.empty();
        }
        List<RepairEstimateCatalogNode> databaseNodes = dataManager.load(RepairEstimateCatalogNode.class)
                .query("""
                        select e from RepairEstimateCatalogNode e
                        left join fetch e.parent
                        where e.active = true
                        order by coalesce(e.sortOrder, 2147483647), e.name
                        """)
                .list();
        if (databaseNodes.isEmpty()) {
            return CatalogSnapshot.empty();
        }
        List<RepairEstimateCatalogLink> databaseLinks = dataManager.load(RepairEstimateCatalogLink.class)
                .query("""
                        select e from RepairEstimateCatalogLink e
                        left join fetch e.sourceNode
                        left join fetch e.targetNode
                        where e.active = true
                        order by coalesce(e.sortOrder, 2147483647), e.id
                        """)
                .list();
        return buildSnapshot(databaseNodes, databaseLinks);
    }

    private CatalogNode withMainMenuTitle(CatalogNode node) {
        if (node.mainMenuTitle() == null || node.mainMenuTitle().isBlank()) {
            return node;
        }
        return new CatalogNode(
                node.code(),
                node.type(),
                node.mainMenuTitle(),
                node.mainMenuTitle(),
                node.unit(),
                node.unitPrice(),
                node.defaultQuantity(),
                node.includeInEstimate(),
                node.commonItem(),
                node.furnitureCategory(),
                node.additionalOption(),
                node.showInMainMenu(),
                node.mainMenuOrder(),
                node.mainMenuTitle());
    }

    private void addLinkedCode(Map<String, List<String>> linksBySource, String sourceCode, String targetCode) {
        if (sourceCode == null || targetCode == null) {
            return;
        }
        linksBySource.computeIfAbsent(sourceCode, ignored -> new ArrayList<>());
        List<String> targets = linksBySource.get(sourceCode);
        if (!targets.contains(targetCode)) {
            targets.add(targetCode);
        }
    }

    private CatalogNodeType mapNodeType(RepairEstimateCatalogNode node) {
        if (node == null || node.getNodeType() == null) {
            return CatalogNodeType.GROUP;
        }
        return switch (node.getNodeType()) {
            case CATEGORY -> CatalogNodeType.CATEGORY;
            case SUBCATEGORY -> CatalogNodeType.SUBCATEGORY;
            case WORK -> CatalogNodeType.WORK;
            case MATERIAL -> CatalogNodeType.MATERIAL;
            case LOCATION -> CatalogNodeType.LOCATION;
            case OPTION -> CatalogNodeType.OPTION;
        };
    }

    private String shortTitle(RepairEstimateCatalogNode node) {
        String title = blankToNull(node.getMainMenuTitle());
        if (title != null && Boolean.TRUE.equals(node.getShowInMainMenu())) {
            return title;
        }
        return blankToNull(node.getName());
    }

    private Integer defaultQuantity(RepairEstimateCatalogNode node) {
        return node.getDefaultQuantity() == null || node.getDefaultQuantity() < 1
                ? 1
                : node.getDefaultQuantity();
    }

    private int compareNullable(Integer leftOrder, Integer rightOrder, String leftName, String rightName) {
        int left = leftOrder == null ? Integer.MAX_VALUE : leftOrder;
        int right = rightOrder == null ? Integer.MAX_VALUE : rightOrder;
        int compare = Integer.compare(left, right);
        if (compare != 0) {
            return compare;
        }
        return safe(leftName).compareToIgnoreCase(safe(rightName));
    }

    private String normalizeCode(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }

    private String blankToNull(String value) {
        String normalized = safe(value);
        return normalized.isBlank() ? null : normalized;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public enum CatalogNodeType {
        CATEGORY,
        GROUP,
        SUBCATEGORY,
        WORK,
        MATERIAL,
        LOCATION,
        OPTION,
        MANUAL
    }

    public record CatalogNode(
            String code,
            CatalogNodeType type,
            String title,
            String shortTitle,
            String unit,
            BigDecimal unitPrice,
            Integer defaultQuantity,
            boolean includeInEstimate,
            boolean commonItem,
            boolean furnitureCategory,
            boolean additionalOption,
            boolean showInMainMenu,
            Integer mainMenuOrder,
            String mainMenuTitle
    ) {
    }

    private record MainMenuNode(CatalogNode node, Integer order) {
    }

    record CatalogSnapshot(
            Map<String, CatalogNode> nodes,
            Map<String, List<String>> childrenByParent,
            Map<String, List<String>> dependencyLinksBySource,
            Map<String, List<String>> followUpLinksBySource,
            Map<String, String> parentByChild,
            Set<String> childCodes,
            List<CatalogNode> rootNodes,
            List<CatalogNode> mainMenuNodes
    ) {
        private static CatalogSnapshot empty() {
            return new CatalogSnapshot(
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Set.of(),
                    List.of(),
                    List.of());
        }
    }
}
