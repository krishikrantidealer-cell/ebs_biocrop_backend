package com.ebs.biocrop.entity;

import com.ebs.biocrop.entity.enums.BlogStatus;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One article and its bounded display metadata in the blogs collection. */
@Document(collection = "blogs")
public class Blog {

    @Id
    private String id;

    private String title;

    @Indexed(unique = true, name = "uniq_blog_slug")
    private String slug;

    private String excerpt;
    private BlogContent content = new BlogContent();
    private BlogCategory category;
    private List<String> tags = new ArrayList<>();
    private FeaturedImage featuredImage;
    private BlogStatus status = BlogStatus.DRAFT;
    private Instant scheduledAt;
    private Instant publishedAt;
    private ObjectId publishedBy;
    private BlogSeo seo = new BlogSeo();
    private List<ObjectId> relatedBlogIds = new ArrayList<>();
    private List<RelatedProduct> relatedProducts = new ArrayList<>();
    private Integer wordCount = 0;
    private Integer readingTimeMinutes = 1;
    private Boolean isFeatured = false;
    private ObjectId createdBy;
    private ObjectId updatedBy;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant deletedAt;

    @Version
    private Long version;

    public Blog() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getExcerpt() { return excerpt; }
    public void setExcerpt(String excerpt) { this.excerpt = excerpt; }
    public BlogContent getContent() { return content; }
    public void setContent(BlogContent content) { this.content = content != null ? content : new BlogContent(); }
    public BlogCategory getCategory() { return category; }
    public void setCategory(BlogCategory category) { this.category = category; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags != null ? tags : new ArrayList<>(); }
    public FeaturedImage getFeaturedImage() { return featuredImage; }
    public void setFeaturedImage(FeaturedImage featuredImage) { this.featuredImage = featuredImage; }
    public BlogStatus getStatus() { return status; }
    public void setStatus(BlogStatus status) { this.status = status != null ? status : BlogStatus.DRAFT; }
    public Instant getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(Instant scheduledAt) { this.scheduledAt = scheduledAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    public ObjectId getPublishedBy() { return publishedBy; }
    public void setPublishedBy(ObjectId publishedBy) { this.publishedBy = publishedBy; }
    public BlogSeo getSeo() { return seo; }
    public void setSeo(BlogSeo seo) { this.seo = seo != null ? seo : new BlogSeo(); }
    public List<ObjectId> getRelatedBlogIds() { return relatedBlogIds; }
    public void setRelatedBlogIds(List<ObjectId> relatedBlogIds) { this.relatedBlogIds = relatedBlogIds != null ? relatedBlogIds : new ArrayList<>(); }
    public List<RelatedProduct> getRelatedProducts() { return relatedProducts; }
    public void setRelatedProducts(List<RelatedProduct> relatedProducts) { this.relatedProducts = relatedProducts != null ? relatedProducts : new ArrayList<>(); }
    public Integer getWordCount() { return wordCount; }
    public void setWordCount(Integer wordCount) { this.wordCount = wordCount; }
    public Integer getReadingTimeMinutes() { return readingTimeMinutes; }
    public void setReadingTimeMinutes(Integer readingTimeMinutes) { this.readingTimeMinutes = readingTimeMinutes; }
    public Boolean getIsFeatured() { return isFeatured; }
    public void setIsFeatured(Boolean featured) { this.isFeatured = featured; }
    public ObjectId getCreatedBy() { return createdBy; }
    public void setCreatedBy(ObjectId createdBy) { this.createdBy = createdBy; }
    public ObjectId getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(ObjectId updatedBy) { this.updatedBy = updatedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public static class BlogContent {
        private String format = "TIPTAP_JSON";
        private Integer version = 1;
        private Map<String, Object> body = new LinkedHashMap<>(Map.of("type", "doc", "content", List.of()));

        public String getFormat() { return format; }
        public void setFormat(String format) { this.format = format; }
        public Integer getVersion() { return version; }
        public void setVersion(Integer version) { this.version = version; }
        public Map<String, Object> getBody() { return body; }
        public void setBody(Map<String, Object> body) { this.body = body != null ? body : new LinkedHashMap<>(); }
    }

    public static class BlogCategory {
        private String name;
        private String slug;

        public BlogCategory() { }
        public BlogCategory(String name, String slug) { this.name = name; this.slug = slug; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getSlug() { return slug; }
        public void setSlug(String slug) { this.slug = slug; }
    }

    public static class FeaturedImage {
        private String url;
        private String altText;
        private String caption;
        private Integer width;
        private Integer height;
        private String mimeType;
        private Long sizeBytes;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getAltText() { return altText; }
        public void setAltText(String altText) { this.altText = altText; }
        public String getCaption() { return caption; }
        public void setCaption(String caption) { this.caption = caption; }
        public Integer getWidth() { return width; }
        public void setWidth(Integer width) { this.width = width; }
        public Integer getHeight() { return height; }
        public void setHeight(Integer height) { this.height = height; }
        public String getMimeType() { return mimeType; }
        public void setMimeType(String mimeType) { this.mimeType = mimeType; }
        public Long getSizeBytes() { return sizeBytes; }
        public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    }

    public static class BlogSeo {
        private String metaTitle;
        private String metaDescription;
        private String canonicalUrl;
        private String ogTitle;
        private String ogDescription;
        private String ogImageUrl;
        private Boolean noIndex = true;

        public String getMetaTitle() { return metaTitle; }
        public void setMetaTitle(String metaTitle) { this.metaTitle = metaTitle; }
        public String getMetaDescription() { return metaDescription; }
        public void setMetaDescription(String metaDescription) { this.metaDescription = metaDescription; }
        public String getCanonicalUrl() { return canonicalUrl; }
        public void setCanonicalUrl(String canonicalUrl) { this.canonicalUrl = canonicalUrl; }
        public String getOgTitle() { return ogTitle; }
        public void setOgTitle(String ogTitle) { this.ogTitle = ogTitle; }
        public String getOgDescription() { return ogDescription; }
        public void setOgDescription(String ogDescription) { this.ogDescription = ogDescription; }
        public String getOgImageUrl() { return ogImageUrl; }
        public void setOgImageUrl(String ogImageUrl) { this.ogImageUrl = ogImageUrl; }
        public Boolean getNoIndex() { return noIndex; }
        public void setNoIndex(Boolean noIndex) { this.noIndex = noIndex; }
    }

    public static class RelatedProduct {
        private ObjectId productId;
        private String title;

        public RelatedProduct() { }
        public RelatedProduct(ObjectId productId, String title) { this.productId = productId; this.title = title; }
        public ObjectId getProductId() { return productId; }
        public void setProductId(ObjectId productId) { this.productId = productId; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
    }
}
