package com.ebs.biocrop.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;

public class CollectionWriteRequest {
    @NotBlank @Size(max = 120) private String name;
    @Size(max = 140) private String slug;
    @Size(max = 2000) private String description;
    private Boolean isActive;
    @Min(0) private Integer priority;
    @Size(max = 100) @Valid private List<SubCollectionInput> subCollections;
    @Size(max = 160) private String bannerTitle;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean active) { isActive = active; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
    public List<SubCollectionInput> getSubCollections() { return subCollections; }
    public void setSubCollections(List<SubCollectionInput> subCollections) { this.subCollections = subCollections; }
    public String getBannerTitle() { return bannerTitle; }
    public void setBannerTitle(String bannerTitle) { this.bannerTitle = bannerTitle; }

    public static class SubCollectionInput {
        @Size(max = 64) private String id;
        @NotBlank @Size(max = 120) private String name;
        @Size(max = 140) private String slug;
        private Boolean isActive;
        @Size(max = 2048) private String image;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getSlug() { return slug; }
        public void setSlug(String slug) { this.slug = slug; }
        public Boolean getIsActive() { return isActive; }
        public void setIsActive(Boolean active) { isActive = active; }
        public String getImage() { return image; }
        public void setImage(String image) { this.image = image; }
    }
}
