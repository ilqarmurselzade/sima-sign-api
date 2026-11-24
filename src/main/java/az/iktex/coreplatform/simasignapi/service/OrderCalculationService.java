package az.iktex.coreplatform.simasignapi.service;

import az.iktex.coreplatform.simasignapi.dto.OrderItemRequest;
import az.iktex.coreplatform.simasignapi.dto.OrderItemResponse;
import az.iktex.coreplatform.simasignapi.dto.OrderRequest;
import az.iktex.coreplatform.simasignapi.dto.OrderResponse;
import az.iktex.coreplatform.simasignapi.enums.CustomerType;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Service
public class OrderCalculationService {

    private static final int SCALE = 2;
    private static final BigDecimal ZERO_AMOUNT = BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);

    private static final int QUANTITY_DISCOUNT_THRESHOLD = 10;
    private static final BigDecimal QUANTITY_DISCOUNT_RATE = new BigDecimal("0.05");
    private static final BigDecimal VIP_DISCOUNT_RATE = new BigDecimal("0.15");
    private static final BigDecimal YENI10_RATE = new BigDecimal("0.10");
    private static final BigDecimal ENDIRIM20_AMOUNT = new BigDecimal("20.00");
    private static final BigDecimal ENDIRIM20_MIN_SUBTOTAL = new BigDecimal("100.00");
    private static final BigDecimal MAX_DISCOUNT_RATE = new BigDecimal("0.30");
    private static final BigDecimal VAT_RATE = new BigDecimal("0.18");
    private static final BigDecimal FREE_DELIVERY_THRESHOLD = new BigDecimal("200.00");
    private static final BigDecimal BAKU_DELIVERY_FEE = new BigDecimal("5.00");
    private static final BigDecimal OTHER_CITY_DELIVERY_FEE = new BigDecimal("10.00");

    private static final String PROMO_YENI10 = "YENI10";
    private static final String PROMO_ENDIRIM20 = "ENDIRIM20";
    private static final List<String> BAKU_NAMES = List.of("Bakı", "BAKI", "Baku");

    private static final String RULE_QUANTITY = "Say endirimi 5%: ";
    private static final String RULE_VIP = "VIP endirimi 15%";
    private static final String RULE_YENI10 = "YENI10 promo kodu 10%";
    private static final String RULE_ENDIRIM20 = "ENDIRIM20 promo kodu 20.00 AZN";
    private static final String RULE_ENDIRIM20_FAILED = "ENDIRIM20 tətbiq olunmadı: minimum məbləğ 100.00 AZN";
    private static final String RULE_DISCOUNT_LIMIT = "Endirim limiti tətbiq olundu: maksimum 30%";
    private static final String RULE_FREE_DELIVERY = "Pulsuz çatdırılma";

    public OrderResponse calculate(OrderRequest request) {
        if (!isValid(request)) {
            return null;
        }

        List<String> appliedRules = new ArrayList<>();

        // 1-3: sətirlər və subtotal
        List<OrderItemResponse> lines = new ArrayList<>();
        BigDecimal subtotal = ZERO_AMOUNT;
        for (OrderItemRequest item : request.getItems()) {
            OrderItemResponse line = calculateLine(item);
            if (line.getQuantityDiscount().signum() > 0) {
                appliedRules.add(RULE_QUANTITY + line.getProductName());
            }
            lines.add(line);
            subtotal = subtotal.add(line.getFinalLineTotal());
        }

        // 4: müştəri endirimi
        BigDecimal customerDiscount = ZERO_AMOUNT;
        if (request.getCustomerType() == CustomerType.VIP) {
            customerDiscount = percentOf(subtotal, VIP_DISCOUNT_RATE);
            appliedRules.add(RULE_VIP);
        }

        // 5: promo kod
        BigDecimal promoDiscount = calculatePromoDiscount(request.getPromoCode(), subtotal);
        addPromoRule(request.getPromoCode(), promoDiscount, appliedRules);

        // 6: endirim limiti
        BigDecimal totalDiscount = customerDiscount.add(promoDiscount);
        BigDecimal maxDiscount = percentOf(subtotal, MAX_DISCOUNT_RATE);
        if (totalDiscount.compareTo(maxDiscount) > 0) {
            totalDiscount = maxDiscount;
            appliedRules.add(RULE_DISCOUNT_LIMIT);
        }

        // 7: endirimdən sonra
        BigDecimal amountAfterDiscount = subtotal.subtract(totalDiscount);

        // 8: ƏDV
        BigDecimal vat = percentOf(amountAfterDiscount, VAT_RATE);

        // 9: çatdırılma
        BigDecimal deliveryFee = calculateDeliveryFee(amountAfterDiscount, request.getDeliveryCity());
        if (deliveryFee.signum() == 0) {
            appliedRules.add(RULE_FREE_DELIVERY);
        }

        // 10: yekun
        BigDecimal grandTotal = amountAfterDiscount.add(vat).add(deliveryFee).setScale(SCALE, RoundingMode.HALF_UP);

        OrderResponse response = new OrderResponse();
        response.setItems(lines);
        response.setSubtotal(subtotal);
        response.setCustomerDiscount(customerDiscount);
        response.setPromoDiscount(promoDiscount);
        response.setTotalDiscount(totalDiscount);
        response.setAmountAfterDiscount(amountAfterDiscount);
        response.setVat(vat);
        response.setDeliveryFee(deliveryFee);
        response.setGrandTotal(grandTotal);
        response.setAppliedRules(appliedRules);
        return response;
    }

    private boolean isValid(OrderRequest request) {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            System.out.println("XƏTA: Sifarişdə ən azı bir məhsul olmalıdır");
            return false;
        }
        for (OrderItemRequest item : request.getItems()) {
            if (item == null || item.getProductName() == null || item.getProductName().isBlank()) {
                System.out.println("XƏTA: Məhsulun adı boş ola bilməz");
                return false;
            }
            if (item.getQuantity() == null || item.getQuantity() < 1) {
                System.out.println("XƏTA: Say 1-dən kiçik ola bilməz");
                return false;
            }
            if (item.getUnitPrice() == null || item.getUnitPrice().signum() <= 0) {
                System.out.println("XƏTA: Qiymət 0-dan böyük olmalıdır");
                return false;
            }
        }
        if (request.getCustomerType() == null) {
            System.out.println("XƏTA: Müştəri tipi mütləqdir");
            return false;
        }
        if (request.getDeliveryCity() == null || request.getDeliveryCity().isBlank()) {
            System.out.println("XƏTA: Çatdırılma şəhəri mütləqdir");
            return false;
        }
        return true;
    }

    private OrderItemResponse calculateLine(OrderItemRequest item) {
        BigDecimal lineTotal = item.getUnitPrice()
                .multiply(BigDecimal.valueOf(item.getQuantity()))
                .setScale(SCALE, RoundingMode.HALF_UP);

        BigDecimal quantityDiscount = ZERO_AMOUNT;
        if (item.getQuantity() >= QUANTITY_DISCOUNT_THRESHOLD) {
            quantityDiscount = percentOf(lineTotal, QUANTITY_DISCOUNT_RATE);
        }

        OrderItemResponse line = new OrderItemResponse();
        line.setProductName(item.getProductName());
        line.setUnitPrice(item.getUnitPrice());
        line.setQuantity(item.getQuantity());
        line.setLineTotal(lineTotal);
        line.setQuantityDiscount(quantityDiscount);
        line.setFinalLineTotal(lineTotal.subtract(quantityDiscount));
        return line;
    }

    private BigDecimal calculatePromoDiscount(String code, BigDecimal subtotal) {
        if (code == null || code.isBlank()) {
            return ZERO_AMOUNT;
        }
        String normalized = code.trim();
        if (PROMO_YENI10.equalsIgnoreCase(normalized)) {
            return percentOf(subtotal, YENI10_RATE);
        }
        if (PROMO_ENDIRIM20.equalsIgnoreCase(normalized)) {
            if (subtotal.compareTo(ENDIRIM20_MIN_SUBTOTAL) >= 0) {
                return ENDIRIM20_AMOUNT.setScale(SCALE, RoundingMode.HALF_UP);
            }
            return ZERO_AMOUNT;
        }
        System.out.println("XƏBƏRDARLIQ: Promo kod tapılmadı: " + code);
        return ZERO_AMOUNT;
    }

    private BigDecimal calculateDeliveryFee(BigDecimal amount, String city) {
        if (amount.compareTo(FREE_DELIVERY_THRESHOLD) >= 0) {
            return ZERO_AMOUNT;
        }
        String trimmedCity = city.trim();
        for (String bakuName : BAKU_NAMES) {
            if (bakuName.equalsIgnoreCase(trimmedCity)) {
                return BAKU_DELIVERY_FEE.setScale(SCALE, RoundingMode.HALF_UP);
            }
        }
        return OTHER_CITY_DELIVERY_FEE.setScale(SCALE, RoundingMode.HALF_UP);
    }

    private void addPromoRule(String code, BigDecimal promoDiscount, List<String> rules) {
        if (code == null || code.isBlank()) {
            return;
        }
        String normalized = code.trim();
        if (PROMO_YENI10.equalsIgnoreCase(normalized)) {
            rules.add(RULE_YENI10);
        } else if (PROMO_ENDIRIM20.equalsIgnoreCase(normalized)) {
            rules.add(promoDiscount.signum() > 0 ? RULE_ENDIRIM20 : RULE_ENDIRIM20_FAILED);
        }
    }

    private BigDecimal percentOf(BigDecimal base, BigDecimal rate) {
        return base.multiply(rate).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
