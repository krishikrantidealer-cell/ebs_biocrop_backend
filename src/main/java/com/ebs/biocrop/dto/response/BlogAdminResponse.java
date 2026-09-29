package com.ebs.biocrop.dto.response;

import com.ebs.biocrop.entity.Blog;
import com.ebs.biocrop.entity.enums.BlogStatus;

import java.time.Instant;
import java.util.List;

public record BlogAdminResponse(
        String id,
        String title,
        String slug,
        String excerpt,
        Blog.BlogContent content,
        Blog.BlogCategory category,
        List<String> tags,
        Blog.FeaturedImage featuredImage,
        BlogStatus status,
        Instant scheduledAt,
        Instant publishedAt,
        String publishedBy,
        Blog.BlogSeo seo,
        List<String> relatedBlogIds,
        List<Blog.RelatedProduct> relatedProducts,
        Integer wordCount,
        Integer readingTimeMinutes,
        Boolean isFeatured,
        String createdBy,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt,
        Long version) {

    public static BlogAdminResponse from(Blog blog) {
        return new BlogAdminResponse(
                blog.getId(), blog.getTitle(), blog.getSlug(), blog.getExcerpt(), blog.getContent(),
                blog.getCategory(), safeList(blog.getTags()), blog.getFeaturedImage(), blog.getStatus(),
                blog.getScheduledAt(), blog.getPublishedAt(), hex(blog.getPublishedBy()), blog.getSeo(),
                safeList(blog.getRelatedBlogIds()).stream().map(Object::toString).toList(), safeList(blog.getRelatedProducts()),
                blog.getWordCount(), blog.getReadingTimeMinutes(), blog.getIsFeatured(), hex(blog.getCreatedBy()),
                hex(blog.getUpdatedBy()), blog.getCreatedAt(), blog.getUpdatedAt(), blog.getDeletedAt(), blog.getVersion());
    }

    private static String hex(org.bson.types.ObjectId value) {
        return value == null ? null : value.toHexString();
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
