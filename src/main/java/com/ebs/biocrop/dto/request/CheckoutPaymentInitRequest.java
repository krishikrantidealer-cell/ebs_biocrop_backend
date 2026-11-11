package com.ebs.biocrop.dto.request;

import com.ebs.biocrop.entity.OrderPaymentMethod;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public class CheckoutPaymentInitRequest {

    @NotNull(message = "Payment method is required")
    private OrderPaymentMethod paymentMethod = OrderPaymentMethod.PREPAID;

    private BigDecimal advancePercentage;

    public OrderPaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(OrderPaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }
    public BigDecimal getAdvancePercentage() { return advancePercentage; }
    public void setAdvancePercentage(BigDecimal advancePercentage) { this.advancePercentage = advancePercentage; }
}
