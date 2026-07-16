package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@JmixEntity
@Table(name = "WAREHOUSE_SEGMENT", uniqueConstraints = {
        @UniqueConstraint(name = "IDX_WAREHOUSE_SEGMENT_UNQ_NAME", columnNames = "NAME")
})
@Entity
public class WarehouseSegment extends WarehouseDictionaryEntity {
}
