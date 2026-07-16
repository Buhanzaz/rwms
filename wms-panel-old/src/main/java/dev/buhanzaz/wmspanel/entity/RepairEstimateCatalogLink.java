package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.annotation.InstanceName;
import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import java.util.Objects;

@JmixEntity
@Table(name = "REPAIR_ESTIMATE_CATALOG_LINK", uniqueConstraints = {
        @UniqueConstraint(name = "IDX_REPAIR_ESTIMATE_CATALOG_LINK_UNQ", columnNames = {
                "SOURCE_NODE_ID", "TARGET_NODE_ID", "LINK_TYPE"
        })
})
@Entity
public class RepairEstimateCatalogLink extends FullAuditEntity {

    @NotNull
    @JoinColumn(name = "SOURCE_NODE_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private RepairEstimateCatalogNode sourceNode;

    @NotNull
    @JoinColumn(name = "TARGET_NODE_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private RepairEstimateCatalogNode targetNode;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "LINK_TYPE", nullable = false, length = 32)
    private RepairEstimateCatalogLinkType linkType = RepairEstimateCatalogLinkType.DEPENDENCY;

    @NotNull
    @Column(name = "ACTIVE", nullable = false)
    private Boolean active = true;

    @Column(name = "SORT_ORDER")
    private Integer sortOrder;

    @Column(name = "COMMENT_", length = 1000)
    private String comment;

    @InstanceName
    public String getDisplayName() {
        String source = sourceNode == null ? "" : sourceNode.getDisplayName();
        String target = targetNode == null ? "" : targetNode.getDisplayName();
        String type = linkType == null ? "" : linkType.getId();
        return source + " -> " + target + " (" + type + ")";
    }

    @AssertTrue(message = "{validation.repairEstimateCatalogLinkSourceTarget}")
    public boolean isSourceAndTargetDifferent() {
        if (sourceNode == null || targetNode == null || sourceNode.getId() == null || targetNode.getId() == null) {
            return true;
        }
        return !Objects.equals(sourceNode.getId(), targetNode.getId());
    }

    public RepairEstimateCatalogNode getSourceNode() {
        return sourceNode;
    }

    public void setSourceNode(RepairEstimateCatalogNode sourceNode) {
        this.sourceNode = sourceNode;
    }

    public RepairEstimateCatalogNode getTargetNode() {
        return targetNode;
    }

    public void setTargetNode(RepairEstimateCatalogNode targetNode) {
        this.targetNode = targetNode;
    }

    public RepairEstimateCatalogLinkType getLinkType() {
        return linkType;
    }

    public void setLinkType(RepairEstimateCatalogLinkType linkType) {
        this.linkType = linkType;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}
