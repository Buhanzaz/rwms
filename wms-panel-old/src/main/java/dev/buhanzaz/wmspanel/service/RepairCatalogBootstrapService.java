package dev.buhanzaz.wmspanel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogLink;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogLinkType;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogNode;
import dev.buhanzaz.wmspanel.entity.RepairEstimateCatalogNodeType;
import io.jmix.core.DataManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import io.jmix.core.security.SystemAuthenticator;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@Service
public class RepairCatalogBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(RepairCatalogBootstrapService.class);
    private static final UUID STABLE_NAMESPACE = UUID.fromString("8af42a0c-d0a2-4cde-a0f0-9c6a7f16a0b2");
    private static final String SEED_RESOURCE_PATH = "dev/buhanzaz/wmspanel/repaircatalog/repair-catalog-seed.json";

    private final DataManager dataManager;
    private final ObjectMapper objectMapper;
    private final RepairCatalogService repairCatalogService;
    private final SystemAuthenticator systemAuthenticator;
    private final boolean bootstrapEnabled;
    @PersistenceContext
    private EntityManager entityManager;

    public RepairCatalogBootstrapService(DataManager dataManager,
                                         ObjectMapper objectMapper,
                                         RepairCatalogService repairCatalogService,
                                         SystemAuthenticator systemAuthenticator,
                                         @Value("${repair.catalog.bootstrap-enabled:false}") boolean bootstrapEnabled) {
        this.dataManager = dataManager;
        this.objectMapper = objectMapper;
        this.repairCatalogService = repairCatalogService;
        this.systemAuthenticator = systemAuthenticator;
        this.bootstrapEnabled = bootstrapEnabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrapOnApplicationReady() {
        if (!bootstrapEnabled) {
            log.info("Repair catalog bootstrap is disabled");
            return;
        }
        systemAuthenticator.begin("admin");
        try {
            bootstrapCatalog();
        } finally {
            systemAuthenticator.end();
        }
    }

    @Transactional
    public BootstrapResult bootstrapCatalog() {
        CatalogSeed seed = normalizedSeed(loadCatalogSeed());
        if (seed.nodes() == null || seed.nodes().isEmpty()) {
            int linkCount = seed.links() == null ? 0 : seed.links().size();
            log.info("Repair catalog bootstrap skipped: packaged seed is empty: {}", SEED_RESOURCE_PATH);
            return new BootstrapResult(0, 0, 0, linkCount);
        }

        Map<String, RepairEstimateCatalogNode> nodesByCode = loadNodesByCode();
        Set<UUID> existingNodeIds = loadExistingNodeIds();
        int createdNodes = 0;
        for (SeedNode seedNode : seed.nodes()) {
            UUID nodeId = stableUuid("repair-catalog-node:" + seedNode.code());
            if (nodesByCode.containsKey(seedNode.code()) || existingNodeIds.contains(nodeId)) {
                continue;
            }
            RepairEstimateCatalogNode node = dataManager.create(RepairEstimateCatalogNode.class);
            node.setId(nodeId);
            node.setCode(seedNode.code());
            node.setName(seedNode.name());
            node.setNodeType(seedNode.nodeType());
            node.setActive(true);
            node.setSortOrder(seedNode.sortOrder());
            node.setUnit(seedNode.unit());
            node.setUnitPrice(seedNode.unitPrice());
            node.setDefaultQuantity(seedNode.defaultQuantity());
            node.setAdditionalOption(seedNode.additionalOption());
            node.setShowInMainMenu(seedNode.showInMainMenu());
            node.setMainMenuOrder(seedNode.mainMenuOrder());
            node.setMainMenuTitle(seedNode.mainMenuTitle());
            if (seedNode.parentCode() != null) {
                RepairEstimateCatalogNode parent = nodesByCode.get(seedNode.parentCode());
                if (parent == null) {
                    throw new IllegalStateException("Packaged repair catalog seed parent is missing: "
                            + seedNode.parentCode() + " for node " + seedNode.code());
                }
                node.setParent(parent);
            }
            RepairEstimateCatalogNode saved = dataManager.save(node);
            nodesByCode.put(seedNode.code(), saved);
            createdNodes++;
        }

        Set<String> existingLinks = loadExistingLinks();
        Set<UUID> existingLinkIds = loadExistingLinkIds();
        int createdLinks = 0;
        for (SeedLink seedLink : seed.links()) {
            String key = linkKey(seedLink.sourceCode(), seedLink.targetCode(), seedLink.linkType());
            UUID linkId = stableUuid("repair-catalog-link:" + seedLink.linkType() + ":" + seedLink.sourceCode() + ":" + seedLink.targetCode());
            if (existingLinks.contains(key) || existingLinkIds.contains(linkId)) {
                continue;
            }
            RepairEstimateCatalogNode sourceNode = nodesByCode.get(seedLink.sourceCode());
            RepairEstimateCatalogNode targetNode = nodesByCode.get(seedLink.targetCode());
            if (sourceNode == null || targetNode == null) {
                throw new IllegalStateException("Packaged repair catalog seed link references missing nodes: "
                        + seedLink.sourceCode() + " -> " + seedLink.targetCode() + " [" + seedLink.linkType() + "]");
            }
            RepairEstimateCatalogLink link = dataManager.create(RepairEstimateCatalogLink.class);
            link.setId(linkId);
            link.setSourceNode(sourceNode);
            link.setTargetNode(targetNode);
            link.setLinkType(seedLink.linkType());
            link.setActive(true);
            link.setSortOrder(seedLink.sortOrder());
            dataManager.save(link);
            existingLinks.add(key);
            createdLinks++;
        }

        if (createdNodes > 0 || createdLinks > 0) {
            repairCatalogService.clearCache();
            log.info("Repair catalog bootstrap added {} nodes and {} links from {}", createdNodes, createdLinks, SEED_RESOURCE_PATH);
        } else {
            log.info("Repair catalog bootstrap skipped changes: {} already applied", SEED_RESOURCE_PATH);
        }
        return new BootstrapResult(createdNodes, createdLinks, seed.nodes().size(), seed.links().size());
    }

    CatalogSeed loadCatalogSeed() {
        ClassPathResource resource = new ClassPathResource(SEED_RESOURCE_PATH);
        if (!resource.exists()) {
            throw new IllegalStateException("Packaged repair catalog seed was not found: " + SEED_RESOURCE_PATH);
        }
        try (InputStream inputStream = resource.getInputStream()) {
            return objectMapper.readValue(inputStream, CatalogSeed.class);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load packaged repair catalog seed: " + SEED_RESOURCE_PATH, ex);
        }
    }

    private CatalogSeed normalizedSeed(CatalogSeed seed) {
        if (seed == null) {
            throw new IllegalStateException("Packaged repair catalog seed is null: " + SEED_RESOURCE_PATH);
        }
        List<SeedNode> normalizedNodes = seed.nodes().stream()
                .map(this::normalizeNode)
                .toList();
        return new CatalogSeed(normalizedNodes, seed.links());
    }

    private SeedNode normalizeNode(SeedNode node) {
        if (node == null) {
            throw new IllegalStateException("Packaged repair catalog seed contains null node");
        }
        return node;
    }

    private Map<String, RepairEstimateCatalogNode> loadNodesByCode() {
        Map<String, RepairEstimateCatalogNode> result = new LinkedHashMap<>();
        for (RepairEstimateCatalogNode node : dataManager.load(RepairEstimateCatalogNode.class)
                .query("select e from RepairEstimateCatalogNode e")
                .list()) {
            if (node.getCode() != null && !node.getCode().isBlank()) {
                result.put(node.getCode().trim().toUpperCase(), node);
            }
        }
        return result;
    }

    private Set<String> loadExistingLinks() {
        Set<String> result = new LinkedHashSet<>();
        for (RepairEstimateCatalogLink link : dataManager.load(RepairEstimateCatalogLink.class)
                .query("""
                        select e from RepairEstimateCatalogLink e
                        left join fetch e.sourceNode
                        left join fetch e.targetNode
                        """)
                .list()) {
            if (link.getSourceNode() == null || link.getTargetNode() == null || link.getLinkType() == null) {
                continue;
            }
            String source = normalizeCode(link.getSourceNode().getCode());
            String target = normalizeCode(link.getTargetNode().getCode());
            if (source != null && target != null) {
                result.add(linkKey(source, target, link.getLinkType()));
            }
        }
        return result;
    }

    private Set<UUID> loadExistingNodeIds() {
        return loadExistingIds("select ID from REPAIR_ESTIMATE_CATALOG_NODE");
    }

    private Set<UUID> loadExistingLinkIds() {
        return loadExistingIds("select ID from REPAIR_ESTIMATE_CATALOG_LINK");
    }

    private Set<UUID> loadExistingIds(String sql) {
        Set<UUID> result = new LinkedHashSet<>();
        @SuppressWarnings("unchecked")
        List<Object> rawIds = entityManager.createNativeQuery(sql).getResultList();
        for (Object rawId : rawIds) {
            UUID id = toUuid(rawId);
            if (id != null) {
                result.add(id);
            }
        }
        return result;
    }

    private UUID toUuid(Object rawId) {
        if (rawId instanceof UUID uuid) {
            return uuid;
        }
        if (rawId instanceof String value && !value.isBlank()) {
            return UUID.fromString(value);
        }
        return null;
    }

    private UUID stableUuid(String seed) {
        return UUID.nameUUIDFromBytes((STABLE_NAMESPACE + ":" + seed).getBytes(StandardCharsets.UTF_8));
    }

    private String normalizeCode(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase();
        return normalized.isBlank() ? null : normalized;
    }

    private String linkKey(String sourceCode, String targetCode, RepairEstimateCatalogLinkType linkType) {
        return sourceCode + "->" + targetCode + ":" + linkType.name();
    }

    public record BootstrapResult(int createdNodes, int createdLinks, int totalSeedNodes, int totalSeedLinks) {
    }

    public record CatalogSeed(List<SeedNode> nodes, List<SeedLink> links) {
    }

    public record SeedNode(
            String code,
            String name,
            RepairEstimateCatalogNodeType nodeType,
            String parentCode,
            String unit,
            BigDecimal unitPrice,
            Integer defaultQuantity,
            boolean additionalOption,
            boolean showInMainMenu,
            Integer mainMenuOrder,
            String mainMenuTitle,
            int sortOrder
    ) {
    }

    public record SeedLink(String sourceCode, String targetCode, RepairEstimateCatalogLinkType linkType, int sortOrder) {
    }
}
