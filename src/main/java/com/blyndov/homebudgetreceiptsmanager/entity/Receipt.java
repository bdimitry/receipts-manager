package com.blyndov.homebudgetreceiptsmanager.entity;

import jakarta.persistence.Column;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(name = "receipts")
public class Receipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_id")
    private Purchase purchase;

    @Column(name = "original_file_name", nullable = false, length = 255)
    private String originalFileName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private Long fileSize;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private CurrencyCode currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "receipt_country_hint", length = 30)
    private ReceiptCountryHint receiptCountryHint;

    @Column(name = "s3_key", nullable = false, unique = true, length = 1024)
    private String s3Key;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "ocr_status", nullable = false, length = 20)
    private ReceiptOcrStatus ocrStatus;

    @Column(name = "raw_ocr_text", columnDefinition = "TEXT")
    private String rawOcrText;

    @Column(name = "raw_ocr_artifact_json", columnDefinition = "TEXT")
    private String rawOcrArtifactJson;

    @Column(name = "parsed_store_name", length = 255)
    private String parsedStoreName;

    @Column(name = "parsed_total_amount", precision = 19, scale = 2)
    private BigDecimal parsedTotalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "parsed_currency", length = 3)
    private CurrencyCode parsedCurrency;

    @Column(name = "parsed_purchase_date")
    private LocalDate parsedPurchaseDate;

    @Column(name = "normalized_ocr_lines_json", columnDefinition = "TEXT")
    private String normalizedOcrLinesJson;

    @Column(name = "reconstructed_ocr_lines_json", columnDefinition = "TEXT")
    private String reconstructedOcrLinesJson;

    @Column(name = "parser_ready_text", columnDefinition = "TEXT")
    private String parserReadyText;

    @Enumerated(EnumType.STRING)
    @Column(name = "language_detection_source", length = 30)
    private OcrLanguageDetectionSource languageDetectionSource;

    @Column(name = "ocr_profile_strategy", length = 50)
    private String ocrProfileStrategy;

    @Column(name = "ocr_profile_used", length = 50)
    private String ocrProfileUsed;

    @Column(name = "parse_warnings_json", columnDefinition = "TEXT")
    private String parseWarningsJson;

    @Column(name = "weak_parse_quality", nullable = false)
    private boolean weakParseQuality;

    @Column(name = "ocr_confidence_json", columnDefinition = "TEXT")
    private String ocrConfidenceJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "ocr_processing_decision", length = 30)
    private ReceiptProcessingDecision ocrProcessingDecision;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 30)
    private ReceiptReviewStatus reviewStatus;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by_user_id")
    private User reviewedByUser;

    @Column(name = "ocr_error_message", columnDefinition = "TEXT")
    private String ocrErrorMessage;

    @Column(name = "ocr_processed_at")
    private Instant ocrProcessedAt;

    @OneToMany(mappedBy = "receipt", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineIndex ASC, id ASC")
    private List<ReceiptLineItem> lineItems = new ArrayList<>();

    public void setLineItems(List<ReceiptLineItem> lineItems) {
        this.lineItems.clear();
        if (lineItems == null) {
            return;
        }

        lineItems.forEach(this::addLineItem);
    }

    public void addLineItem(ReceiptLineItem lineItem) {
        lineItem.setReceipt(this);
        this.lineItems.add(lineItem);
    }

    public void clearLineItems() {
        this.lineItems.clear();
    }

    @PrePersist
    void prePersist() {
        if (uploadedAt == null) {
            uploadedAt = Instant.now();
        }
        if (ocrStatus == null) {
            ocrStatus = ReceiptOcrStatus.NEW;
        }
        if (reviewStatus == null) {
            reviewStatus = ReceiptReviewStatus.UNREVIEWED;
        }
    }

    @PreUpdate
    void preUpdate() {
        if (ocrStatus == null) {
            ocrStatus = ReceiptOcrStatus.NEW;
        }
        if (reviewStatus == null) {
            reviewStatus = ReceiptReviewStatus.UNREVIEWED;
        }
    }
}
