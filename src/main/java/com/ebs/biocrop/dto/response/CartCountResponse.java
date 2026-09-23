package com.ebs.biocrop.dto.response;

public class CartCountResponse {

    private Integer itemCount;
    private Integer totalQuantity;

    public CartCountResponse() {
        this.itemCount = 0;
        this.totalQuantity = 0;
    }

    public CartCountResponse(Integer itemCount, Integer totalQuantity) {
        this.itemCount = itemCount != null ? itemCount : 0;
        this.totalQuantity = totalQuantity != null ? totalQuantity : 0;
    }

    public Integer getItemCount() {
        return itemCount;
    }

    public void setItemCount(Integer itemCount) {
        this.itemCount = itemCount;
    }

    public Integer getTotalQuantity() {
        return totalQuantity;
    }

    public void setTotalQuantity(Integer totalQuantity) {
        this.totalQuantity = totalQuantity;
    }
}
