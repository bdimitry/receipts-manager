package com.blyndov.homebudgetreceiptsmanager.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Table(name = "receipt_corrections")
public class ReceiptCorrection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receipt_id", nullable = false, updatable = false)
    @Setter
    private Receipt receipt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    @Setter
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 30)
    @Setter
    private ReceiptReviewStatus reviewStatus;

    @Column(name = "parsed_snapshot_json", nullable = false, columnDefinition = "TEXT")
    @Setter
    private String parsedSnapshotJson;

    @Column(name = "corrected_snapshot_json", nullable = false, columnDefinition = "TEXT")
    @Setter
    private String correctedSnapshotJson;

    @Column(name = "correction_diff_json", nullable = false, columnDefinition = "TEXT")
    @Setter
    private String correctionDiffJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (reviewStatus == null) {
            reviewStatus = ReceiptReviewStatus.CORRECTED;
        }
    }
}
