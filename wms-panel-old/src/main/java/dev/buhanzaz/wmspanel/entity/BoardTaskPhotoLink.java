package dev.buhanzaz.wmspanel.entity;

import io.jmix.core.metamodel.annotation.JmixEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;

@JmixEntity
@Entity
@Table(name = "BOARD_TASK_PHOTO_LINK")
public class BoardTaskPhotoLink extends UuidEntity {

    @NotNull
    @JoinColumn(name = "BOARD_TASK_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private BoardTask boardTask;

    @NotNull
    @JoinColumn(name = "PHOTO_ID", nullable = false)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private RentalItemEventPhoto photo;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "PHOTO_TYPE", nullable = false, length = 32)
    private BoardTaskPhotoType photoType = BoardTaskPhotoType.WORK;

    @Column(name = "SORT_ORDER", nullable = false)
    private Integer sortOrder = 0;

    public BoardTask getBoardTask() {
        return boardTask;
    }

    public void setBoardTask(BoardTask boardTask) {
        this.boardTask = boardTask;
    }

    public RentalItemEventPhoto getPhoto() {
        return photo;
    }

    public void setPhoto(RentalItemEventPhoto photo) {
        this.photo = photo;
    }

    public BoardTaskPhotoType getPhotoType() {
        return photoType;
    }

    public void setPhotoType(BoardTaskPhotoType photoType) {
        this.photoType = photoType;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }
}
