package com.ebs.biocrop.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.LocalDateTime;

@Document(collection = "hero_banners")
public class HeroBanner {
    @Id
    private String id;
    @Field(write = Field.Write.ALWAYS)
    private String title;
    @Field(write = Field.Write.ALWAYS)
    private String subtitle;
    @Field(write = Field.Write.ALWAYS)
    private String tag;
    @Field(write = Field.Write.ALWAYS)
    private String buttonText;
    @Field(write = Field.Write.ALWAYS)
    private String ctaLink;
    @Field(write = Field.Write.ALWAYS)
    private String imageUri;
    @Field(write = Field.Write.ALWAYS)
    private Integer sortOrder = 0;
    @Field(write = Field.Write.ALWAYS)
    private Boolean active = true;
    @Field(write = Field.Write.ALWAYS)
    private LocalDateTime createdAt;
    @Field(write = Field.Write.ALWAYS)
    private LocalDateTime updatedAt;

    public HeroBanner() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }
    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag; }
    public String getButtonText() { return buttonText; }
    public void setButtonText(String buttonText) { this.buttonText = buttonText; }
    public String getCtaLink() { return ctaLink; }
    public void setCtaLink(String ctaLink) { this.ctaLink = ctaLink; }
    public String getImageUri() { return imageUri; }
    public void setImageUri(String imageUri) { this.imageUri = imageUri; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder == null ? 0 : sortOrder; }
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active == null ? true : active; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
