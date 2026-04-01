package com.blyndov.homebudgetreceiptsmanager.service;

import com.blyndov.homebudgetreceiptsmanager.dto.CreatePurchaseRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.PurchaseItemRequest;
import com.blyndov.homebudgetreceiptsmanager.dto.PurchaseItemResponse;
import com.blyndov.homebudgetreceiptsmanager.dto.PurchaseResponse;
import com.blyndov.homebudgetreceiptsmanager.entity.PurchaseItem;
import com.blyndov.homebudgetreceiptsmanager.entity.Purchase;
import com.blyndov.homebudgetreceiptsmanager.entity.Receipt;
import com.blyndov.homebudgetreceiptsmanager.entity.ReceiptLineItem;
import com.blyndov.homebudgetreceiptsmanager.entity.User;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import com.blyndov.homebudgetreceiptsmanager.exception.ResourceNotFoundException;
import com.blyndov.homebudgetreceiptsmanager.repository.PurchaseRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Transactional(readOnly = true)
public class PurchaseService {

    private static final Sort PURCHASE_SORT = Sort.by(
        Sort.Order.desc("purchaseDate"),
        Sort.Order.desc("createdAt"),
        Sort.Order.desc("id")
    );

    private final PurchaseRepository purchaseRepository;
    private final AuthService authService;

    public PurchaseService(PurchaseRepository purchaseRepository, AuthService authService) {
        this.purchaseRepository = purchaseRepository;
        this.authService = authService;
    }

    @Transactional
    public PurchaseResponse createPurchase(CreatePurchaseRequest request) {
        User currentUser = authService.getCurrentAuthenticatedUser();

        Purchase purchase = new Purchase();
        purchase.setUser(currentUser);
        purchase.setTitle(normalizeRequiredText(request.title()));
        purchase.setCategory(normalizeCategory(request.category()));
        purchase.setAmount(resolvePurchaseAmount(request));
        purchase.setCurrency(request.currency());
        purchase.setPurchaseDate(request.purchaseDate());
        purchase.setStoreName(normalizeOptionalText(request.storeName()));
        purchase.setComment(normalizeOptionalText(request.comment()));
        buildItems(request.items(), purchase).forEach(purchase::addItem);

        return mapToResponse(purchaseRepository.save(purchase));
    }

    @Transactional
    public Purchase createPurchaseFromReceipt(Receipt receipt) {
        if (receipt.getPurchase() != null || !StringUtils.hasText(receipt.getCategory()) || receipt.getParsedTotalAmount() == null) {
            return receipt.getPurchase();
        }

        Purchase purchase = new Purchase();
        purchase.setUser(receipt.getUser());
        purchase.setTitle(resolveReceiptTitle(receipt));
        purchase.setCategory(normalizeCategory(receipt.getCategory()));
        purchase.setAmount(normalizeMoney(receipt.getParsedTotalAmount()));
        purchase.setCurrency(receipt.getCurrency());
        purchase.setPurchaseDate(resolveReceiptPurchaseDate(receipt));
        purchase.setStoreName(normalizeOptionalText(receipt.getParsedStoreName()));
        purchase.setComment("Created from receipt " + receipt.getOriginalFileName());
        buildItemsFromReceipt(receipt, purchase).forEach(purchase::addItem);

        Purchase savedPurchase = purchaseRepository.save(purchase);
        receipt.setPurchase(savedPurchase);
        return savedPurchase;
    }

    public List<PurchaseResponse> getPurchases(Integer year, Integer month, String category) {
        User currentUser = authService.getCurrentAuthenticatedUser();
        String normalizedCategory = StringUtils.hasText(category) ? normalizeCategory(category) : null;

        return purchaseRepository.findAll(hasUserId(currentUser.getId()), PURCHASE_SORT)
            .stream()
            .filter(purchase -> year == null || purchase.getPurchaseDate().getYear() == year)
            .filter(purchase -> month == null || purchase.getPurchaseDate().getMonthValue() == month)
            .filter(purchase -> normalizedCategory == null || purchase.getCategory().equals(normalizedCategory))
            .map(this::mapToResponse)
            .toList();
    }

    public PurchaseResponse getPurchase(Long id) {
        return mapToResponse(getOwnedPurchase(id));
    }

    public Purchase getOwnedPurchaseEntity(Long id) {
        return getOwnedPurchase(id);
    }

    @Transactional
    public void deletePurchase(Long id) {
        purchaseRepository.delete(getOwnedPurchase(id));
    }

    private Purchase getOwnedPurchase(Long id) {
        User currentUser = authService.getCurrentAuthenticatedUser();
        return purchaseRepository.findByIdAndUser_Id(id, currentUser.getId())
            .orElseThrow(() -> new ResourceNotFoundException("Purchase not found"));
    }

    private PurchaseResponse mapToResponse(Purchase purchase) {
        return new PurchaseResponse(
            purchase.getId(),
            purchase.getTitle(),
            purchase.getCategory(),
            purchase.getAmount(),
            purchase.getCurrency(),
            purchase.getPurchaseDate(),
            purchase.getStoreName(),
            purchase.getComment(),
            purchase.getCreatedAt(),
            purchase.getItems().stream()
                .sorted((left, right) -> Integer.compare(left.getLineIndex(), right.getLineIndex()))
                .map(this::mapItemToResponse)
                .toList()
        );
    }

    private PurchaseItemResponse mapItemToResponse(PurchaseItem item) {
        return new PurchaseItemResponse(
            item.getId(),
            item.getLineIndex(),
            item.getTitle(),
            item.getQuantity(),
            item.getUnit(),
            item.getUnitPrice(),
            item.getLineTotal()
        );
    }

    private List<PurchaseItem> buildItems(List<PurchaseItemRequest> itemRequests, Purchase purchase) {
        if (itemRequests == null || itemRequests.isEmpty()) {
            return List.of();
        }

        List<PurchaseItem> items = new ArrayList<>();
        for (int index = 0; index < itemRequests.size(); index++) {
            PurchaseItemRequest request = itemRequests.get(index);
            PurchaseItem item = new PurchaseItem();
            item.setPurchase(purchase);
            item.setLineIndex(index);
            item.setTitle(normalizeRequiredText(request.title()));
            item.setQuantity(request.quantity());
            item.setUnit(normalizeOptionalText(request.unit()));
            item.setUnitPrice(normalizeMoney(request.unitPrice()));
            item.setLineTotal(resolveItemLineTotal(request));
            items.add(item);
        }

        return items;
    }

    private List<PurchaseItem> buildItemsFromReceipt(Receipt receipt, Purchase purchase) {
        if (receipt.getLineItems() == null || receipt.getLineItems().isEmpty()) {
            return List.of();
        }

        List<PurchaseItem> items = new ArrayList<>();
        for (ReceiptLineItem receiptLineItem : receipt.getLineItems()) {
            PurchaseItem item = new PurchaseItem();
            item.setPurchase(purchase);
            item.setLineIndex(receiptLineItem.getLineIndex());
            item.setTitle(resolveReceiptItemTitle(receiptLineItem));
            item.setQuantity(receiptLineItem.getQuantity());
            item.setUnit(normalizeOptionalText(receiptLineItem.getUnit()));
            item.setUnitPrice(normalizeMoney(receiptLineItem.getUnitPrice()));
            item.setLineTotal(normalizeMoney(receiptLineItem.getLineTotal()));
            items.add(item);
        }

        return items;
    }

    private BigDecimal resolvePurchaseAmount(CreatePurchaseRequest request) {
        if (request.items() == null || request.items().isEmpty()) {
            return normalizeMoney(request.amount());
        }

        BigDecimal computedTotal = request.items().stream()
            .map(this::resolveItemLineTotal)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (computedTotal.compareTo(BigDecimal.ZERO) > 0) {
            return normalizeMoney(computedTotal);
        }

        return normalizeMoney(request.amount());
    }

    private BigDecimal resolveItemLineTotal(PurchaseItemRequest request) {
        if (request.lineTotal() != null) {
            return normalizeMoney(request.lineTotal());
        }

        if (request.quantity() != null && request.unitPrice() != null) {
            return normalizeMoney(request.quantity().multiply(request.unitPrice()));
        }

        return null;
    }

    private BigDecimal normalizeMoney(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        return amount.setScale(2, RoundingMode.HALF_UP);
    }

    private Specification<Purchase> hasUserId(Long userId) {
        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(root.get("user").get("id"), userId);
    }

    private String normalizeRequiredText(String value) {
        return value.trim();
    }

    private String normalizeOptionalText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String normalizeCategory(String category) {
        return category.trim().toUpperCase(Locale.ROOT);
    }

    private String resolveReceiptTitle(Receipt receipt) {
        if (receipt.getLineItems() != null && !receipt.getLineItems().isEmpty()) {
            ReceiptLineItem firstLineItem = receipt.getLineItems().get(0);
            if (StringUtils.hasText(firstLineItem.getTitle())) {
                return normalizeRequiredText(firstLineItem.getTitle());
            }
        }

        if (StringUtils.hasText(receipt.getParsedStoreName())) {
            return normalizeRequiredText(receipt.getParsedStoreName());
        }

        return normalizeRequiredText(receipt.getOriginalFileName());
    }

    private LocalDate resolveReceiptPurchaseDate(Receipt receipt) {
        if (receipt.getParsedPurchaseDate() != null) {
            return receipt.getParsedPurchaseDate();
        }

        return receipt.getUploadedAt().atZone(ZoneOffset.UTC).toLocalDate();
    }

    private String resolveReceiptItemTitle(ReceiptLineItem lineItem) {
        if (StringUtils.hasText(lineItem.getTitle())) {
            return normalizeRequiredText(lineItem.getTitle());
        }

        if (StringUtils.hasText(lineItem.getRawFragment())) {
            return normalizeRequiredText(lineItem.getRawFragment());
        }

        return "Receipt item";
    }
}
