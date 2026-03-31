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

@Entity
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

    @Column(name = "s3_key", nullable = false, unique = true, length = 1024)
    private String s3Key;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "ocr_status", nullable = false, length = 20)
    private ReceiptOcrStatus ocrStatus;

    @Column(name = "raw_ocr_text", columnDefinition = "TEXT")
    private String rawOcrText;

    @Column(name = "parsed_store_name", length = 255)
    private String parsedStoreName;

    @Column(name = "parsed_total_amount", precision = 19, scale = 2)
    private BigDecimal parsedTotalAmount;

    @Column(name = "parsed_purchase_date")
    private LocalDate parsedPurchaseDate;

    @Column(name = "ocr_error_message", columnDefinition = "TEXT")
    private String ocrErrorMessage;

    @Column(name = "ocr_processed_at")
    private Instant ocrProcessedAt;

    @OneToMany(mappedBy = "receipt", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineIndex ASC, id ASC")
    private List<ReceiptLineItem> lineItems = new ArrayList<>();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Purchase getPurchase() {
        return purchase;
    }

    public void setPurchase(Purchase purchase) {
        this.purchase = purchase;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public void setOriginalFileName(String originalFileName) {
        this.originalFileName = originalFileName;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public CurrencyCode getCurrency() {
        return currency;
    }

    public void setCurrency(CurrencyCode currency) {
        this.currency = currency;
    }

    public String getS3Key() {
        return s3Key;
    }

    public void setS3Key(String s3Key) {
        this.s3Key = s3Key;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(Instant uploadedAt) {
        this.uploadedAt = uploadedAt;
    }

    public ReceiptOcrStatus getOcrStatus() {
        return ocrStatus;
    }

    public void setOcrStatus(ReceiptOcrStatus ocrStatus) {
        this.ocrStatus = ocrStatus;
    }

    public String getRawOcrText() {
        return rawOcrText;
    }

    public void setRawOcrText(String rawOcrText) {
        this.rawOcrText = rawOcrText;
    }

    public String getParsedStoreName() {
        return parsedStoreName;
    }

    public void setParsedStoreName(String parsedStoreName) {
        this.parsedStoreName = parsedStoreName;
    }

    public BigDecimal getParsedTotalAmount() {
        return parsedTotalAmount;
    }

    public void setParsedTotalAmount(BigDecimal parsedTotalAmount) {
        this.parsedTotalAmount = parsedTotalAmount;
    }

    public LocalDate getParsedPurchaseDate() {
        return parsedPurchaseDate;
    }

    public void setParsedPurchaseDate(LocalDate parsedPurchaseDate) {
        this.parsedPurchaseDate = parsedPurchaseDate;
    }

    public String getOcrErrorMessage() {
        return ocrErrorMessage;
    }

    public void setOcrErrorMessage(String ocrErrorMessage) {
        this.ocrErrorMessage = ocrErrorMessage;
    }

    public Instant getOcrProcessedAt() {
        return ocrProcessedAt;
    }

    public void setOcrProcessedAt(Instant ocrProcessedAt) {
        this.ocrProcessedAt = ocrProcessedAt;
    }

    public List<ReceiptLineItem> getLineItems() {
        return lineItems;
    }

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
    }

    @PreUpdate
    void preUpdate() {
        if (ocrStatus == null) {
            ocrStatus = ReceiptOcrStatus.NEW;
        }
    }
}
