package com.ebs.biocrop.entity;

import com.ebs.biocrop.entity.enums.ReviewStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Document(collection = "reviews")
@CompoundIndexes({
        @CompoundIndex(name = "uniq_customer_product_review", def = "{ 'productId': 1, 'customerId': 1 }", unique = true),
        @CompoundIndex(name = "review_product_status_created_idx", def = "{ 'productId': 1, 'status': 1, 'createdAt': -1 }"),
        @CompoundIndex(name = "review_seller_created_idx", def = "{ 'sellerId': 1, 'createdAt': -1 }")
})
public class Review {

    @Id
    private String id;

    @Indexed
    private String productId;

    @Indexed
    private String sellerId;

    @Indexed
    private String customerId;

    private String firstName;
    private String lastName;
    private String phoneNumber;

    private Integer rating;
    private String title;
    private String comment;

    private List<String> images = new ArrayList<>();
    private String video;

    private Boolean isAnonymous = false;
    private Boolean isVerifiedBuyer = true;

    private String status = ReviewStatus.APPROVED.name();
    private Boolean isDeleted = false;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Review() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public String getSellerId() {
        return sellerId;
    }

    public void setSellerId(String sellerId) {
        this.sellerId = sellerId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public void setCustomerId(String customerId) {
        this.customerId = customerId;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public Integer getRating() {
        return rating;
    }

    public void setRating(Integer rating) {
        this.rating = rating;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public List<String> getImages() {
        return images;
    }

    public void setImages(List<String> images) {
        this.images = images != null ? images : new ArrayList<>();
    }

    public String getVideo() {
        return video;
    }

    public void setVideo(String video) {
        this.video = video;
    }

    public Boolean getIsAnonymous() {
        return isAnonymous;
    }

    public void setIsAnonymous(Boolean anonymous) {
        isAnonymous = anonymous != null ? anonymous : false;
    }

    public Boolean getIsVerifiedBuyer() {
        return isVerifiedBuyer;
    }

    public void setIsVerifiedBuyer(Boolean verifiedBuyer) {
        isVerifiedBuyer = verifiedBuyer != null ? verifiedBuyer : false;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Boolean getIsDeleted() {
        return isDeleted;
    }

    public void setIsDeleted(Boolean deleted) {
        isDeleted = deleted != null ? deleted : false;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
