package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.dto.request.CreateBlogDraftRequest;
import com.ebs.biocrop.dto.request.UpdateBlogRequest;
import com.ebs.biocrop.dto.response.BlogDraftResponse;
import com.ebs.biocrop.dto.response.BlogFacetResponse;
import com.ebs.biocrop.common.pagination.PageRequestSupport;
import com.ebs.biocrop.entity.Blog;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.enums.BlogStatus;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.repository.BlogRepository;
import com.ebs.biocrop.repository.ProductRepository;
import com.ebs.biocrop.service.BlogService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class BlogServiceImpl implements BlogService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_CONTENT_BYTES = 1_000_000;
    private static final int WORDS_PER_MINUTE = 200;
    private static final Set<String> NODE_TYPES = Set.of(
            "doc", "paragraph", "heading", "bulletList", "orderedList", "listItem", "blockquote",
            "table", "tableRow", "tableCell", "image", "horizontalRule", "faq", "text", "hardBreak");
    private static final Set<String> MARK_TYPES = Set.of("bold", "italic", "link");
    private static final Set<String> NODE_ATTRIBUTES = Set.of(
            "level", "href", "target", "rel", "src", "alt", "title", "width", "height",
            "colspan", "rowspan", "colwidth", "start");

    private final BlogRepository blogRepository;
    private final ProductRepository productRepository;
    private final MongoTemplate mongoTemplate;
    private final ObjectMapper objectMapper;

    public BlogServiceImpl(BlogRepository blogRepository, ProductRepository productRepository,
                           MongoTemplate mongoTemplate, ObjectMapper objectMapper) {
        this.blogRepository = blogRepository;
        this.productRepository = productRepository;
        this.mongoTemplate = mongoTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public BlogDraftResponse createDraft(CreateBlogDraftRequest request, ObjectId adminId) {
        String title = request.getTitle().trim();
        String slug = normalizeSlug(hasText(request.getSlug()) ? request.getSlug() : title);
        if (slug.isBlank()) {
            throw new AppException("Provide a URL-safe slug for this title", HttpStatus.BAD_REQUEST);
        }
        if (blogRepository.existsBySlug(slug)) {
            throw duplicateSlug();
        }

        Instant now = Instant.now();
        Blog blog = new Blog();
        blog.setTitle(title);
        blog.setSlug(slug);
        blog.setExcerpt(request.getExcerpt().trim());
        blog.setStatus(BlogStatus.DRAFT);
        blog.setCreatedBy(adminId);
        blog.setUpdatedBy(adminId);
        blog.setCreatedAt(now);
        blog.setUpdatedAt(now);
        try {
            return new BlogDraftResponse(blogRepository.save(blog));
        } catch (DuplicateKeyException exception) {
            throw duplicateSlug();
        }
    }

    @Override
    public Blog getAdminBlog(String id) {
        return findAdminBlog(id);
    }

    @Override
    public Page<Blog> listAdminBlogs(String statusValue, int page, int size) {
        Pageable pageable = pageable(page, size, Sort.by(Sort.Direction.DESC, "updatedAt").and(Sort.by("_id")));
        List<Criteria> filters = new ArrayList<>();
        filters.add(Criteria.where("deletedAt").is(null));
        if (hasText(statusValue)) {
            try {
                filters.add(Criteria.where("status").is(BlogStatus.valueOf(statusValue.trim().toUpperCase(Locale.ROOT))));
            } catch (IllegalArgumentException exception) {
                throw new AppException("Unsupported blog status filter", HttpStatus.BAD_REQUEST);
            }
        }
        Query query = Query.query(new Criteria().andOperator(filters.toArray(Criteria[]::new))).with(pageable);
        long total = mongoTemplate.count(Query.query(new Criteria().andOperator(filters.toArray(Criteria[]::new))), Blog.class);
        return org.springframework.data.support.PageableExecutionUtils.getPage(
                mongoTemplate.find(query, Blog.class), pageable, () -> total);
    }

    @Override
    public Blog updateBlog(String id, UpdateBlogRequest request, ObjectId adminId) {
        Blog blog = findAdminBlog(id);
        if (blog.getStatus() == BlogStatus.ARCHIVED) {
            throw new AppException("Archived blogs cannot be edited", HttpStatus.CONFLICT);
        }

        String title = request.getTitle().trim();
        String slug = normalizeSlug(request.getSlug());
        if (slug.isBlank()) {
            throw new AppException("Slug must contain URL-safe characters", HttpStatus.BAD_REQUEST);
        }
        if (!slug.equals(blog.getSlug()) && blog.getPublishedAt() != null) {
            throw new AppException("A blog slug cannot change after its first publication", HttpStatus.CONFLICT);
        }
        if (!slug.equals(blog.getSlug()) && blogRepository.existsBySlugAndIdNot(slug, blog.getId())) {
            throw duplicateSlug();
        }

        Blog.BlogContent content = request.getContent();
        if (content == null || content.getBody() == null) {
            throw new AppException("Structured blog content is required", HttpStatus.BAD_REQUEST);
        }
        if (!"TIPTAP_JSON".equals(content.getFormat()) || !Integer.valueOf(1).equals(content.getVersion())) {
            throw new AppException("Unsupported blog content format or version", HttpStatus.BAD_REQUEST);
        }
        validateContent(content.getBody());

        blog.setTitle(title);
        blog.setSlug(slug);
        blog.setExcerpt(request.getExcerpt().trim());
        blog.setContent(content);
        blog.setCategory(normalizeCategory(request.getCategory()));
        blog.setTags(normalizeTags(request.getTags()));
        blog.setFeaturedImage(validateFeaturedImage(request.getFeaturedImage()));
        blog.setSeo(normalizeSeo(request.getSeo()));
        blog.setRelatedBlogIds(resolveRelatedBlogs(request.getRelatedBlogIds(), blog.getId()));
        blog.setRelatedProducts(resolveRelatedProducts(request.getRelatedProductIds()));
        if (request.getIsFeatured() != null) {
            blog.setIsFeatured(request.getIsFeatured());
        }
        int words = countWords(content.getBody());
        blog.setWordCount(words);
        blog.setReadingTimeMinutes(Math.max(1, (words + WORDS_PER_MINUTE - 1) / WORDS_PER_MINUTE));
        if (blog.getStatus() == BlogStatus.PUBLISHED || blog.getStatus() == BlogStatus.SCHEDULED) {
            validatePublishGate(blog);
        }
        blog.setUpdatedBy(adminId);
        blog.setUpdatedAt(Instant.now());

        try {
            return blogRepository.save(blog);
        } catch (DuplicateKeyException exception) {
            throw duplicateSlug();
        }
    }

    @Override
    public Blog publishNow(String id, ObjectId adminId) {
        Blog blog = findAdminBlog(id);
        requirePublishableState(blog);
        validatePublishGate(blog);
        Instant now = Instant.now();
        blog.setStatus(BlogStatus.PUBLISHED);
        blog.setScheduledAt(null);
        if (blog.getPublishedAt() == null) {
            blog.setPublishedAt(now);
        }
        blog.setPublishedBy(adminId);
        blog.getSeo().setNoIndex(false);
        blog.setUpdatedBy(adminId);
        blog.setUpdatedAt(now);
        if (blog.getSeo().getNoIndex() == null) {
            blog.getSeo().setNoIndex(false);
        }
        return blogRepository.save(blog);
    }

    @Override
    public Blog schedule(String id, Instant scheduledAt, ObjectId adminId) {
        Blog blog = findAdminBlog(id);
        requirePublishableState(blog);
        if (scheduledAt == null || !scheduledAt.isAfter(Instant.now())) {
            throw new AppException("scheduledAt must be a future timestamp", HttpStatus.BAD_REQUEST);
        }
        validatePublishGate(blog);
        blog.setStatus(BlogStatus.SCHEDULED);
        blog.setScheduledAt(scheduledAt);
        blog.getSeo().setNoIndex(false);
        blog.setUpdatedBy(adminId);
        blog.setUpdatedAt(Instant.now());
        return blogRepository.save(blog);
    }

    @Override
    public Blog unpublish(String id, ObjectId adminId) {
        Blog blog = findAdminBlog(id);
        if (blog.getStatus() != BlogStatus.PUBLISHED) {
            throw new AppException("Only a published blog can be unpublished", HttpStatus.CONFLICT);
        }
        blog.setStatus(BlogStatus.UNPUBLISHED);
        blog.setUpdatedBy(adminId);
        blog.setUpdatedAt(Instant.now());
        return blogRepository.save(blog);
    }

    @Override
    public Blog archive(String id, ObjectId adminId) {
        Blog blog = findAdminBlog(id);
        if (blog.getStatus() == BlogStatus.ARCHIVED) {
            throw new AppException("Blog is already archived", HttpStatus.CONFLICT);
        }
        blog.setStatus(BlogStatus.ARCHIVED);
        blog.setScheduledAt(null);
        blog.setUpdatedBy(adminId);
        blog.setUpdatedAt(Instant.now());
        return blogRepository.save(blog);
    }

    @Override
    public Blog restore(String id, ObjectId adminId) {
        Blog blog = findAdminBlog(id);
        if (blog.getStatus() != BlogStatus.ARCHIVED) {
            throw new AppException("Only an archived blog can be restored", HttpStatus.CONFLICT);
        }
        blog.setStatus(BlogStatus.DRAFT);
        blog.setScheduledAt(null);
        if (blog.getSeo() != null) blog.getSeo().setNoIndex(true);
        blog.setUpdatedBy(adminId);
        blog.setUpdatedAt(Instant.now());
        return blogRepository.save(blog);
    }

    @Override
    public Blog restoreSoftDeleted(String id, ObjectId adminId) {
        if (!ObjectId.isValid(id)) {
            throw new AppException("Blog id must be a valid MongoDB ObjectId", HttpStatus.BAD_REQUEST);
        }
        Blog blog = blogRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Blog", "id", id));
        if (blog.getDeletedAt() == null) {
            throw new AppException("Only a soft-deleted blog can be restored", HttpStatus.CONFLICT);
        }

        Instant now = Instant.now();
        blog.setDeletedAt(null);
        blog.setStatus(BlogStatus.DRAFT);
        blog.setScheduledAt(null);
        if (blog.getSeo() != null) blog.getSeo().setNoIndex(true);
        blog.setUpdatedBy(adminId);
        blog.setUpdatedAt(now);
        return blogRepository.save(blog);
    }

    @Override
    public Blog softDelete(String id, ObjectId adminId) {
        Blog blog = findAdminBlog(id);
        blog.setDeletedAt(Instant.now());
        blog.setUpdatedBy(adminId);
        blog.setUpdatedAt(Instant.now());
        return blogRepository.save(blog);
    }

    @Override
    public Page<Blog> listPublicBlogs(String categorySlug, String tag, Boolean featured, int page, int size) {
        Pageable pageable = pageable(page, size,
                Sort.by(Sort.Direction.DESC, "publishedAt")
                        .and(Sort.by(Sort.Direction.DESC, "_id")));
        Criteria criteria = publicCriteria();
        List<Criteria> filters = new ArrayList<>();
        filters.add(criteria);
        if (hasText(categorySlug)) filters.add(Criteria.where("category.slug").is(normalizeSlug(categorySlug)));
        if (hasText(tag)) filters.add(Criteria.where("tags").is(normalizeTag(tag)));
        if (featured != null) filters.add(Criteria.where("isFeatured").is(featured));
        Criteria combined = new Criteria().andOperator(filters.toArray(Criteria[]::new));
        Query query = Query.query(combined).with(pageable);
        long total = mongoTemplate.count(Query.query(combined), Blog.class);
        return org.springframework.data.support.PageableExecutionUtils.getPage(
                mongoTemplate.find(query, Blog.class), pageable, () -> total);
    }

    @Override
    public Blog getPublicBlog(String slug) {
        return blogRepository.findBySlugAndStatusAndDeletedAtIsNull(normalizeSlug(slug), BlogStatus.PUBLISHED)
                .filter(blog -> blog.getPublishedAt() != null && !blog.getPublishedAt().isAfter(Instant.now()))
                .orElseThrow(() -> new ResourceNotFoundException("Blog", "slug", slug));
    }

    @Override
    public List<BlogFacetResponse> publicCategories() {
        return mongoTemplate.aggregate(Aggregation.newAggregation(
                        Aggregation.match(publicCriteria()),
                        Aggregation.match(Criteria.where("category.slug").exists(true)),
                        Aggregation.group("category.slug")
                                .first("category.name").as("name")
                                .first("category.slug").as("slug"),
                        Aggregation.sort(Sort.Direction.ASC, "name"),
                        Aggregation.limit(PageRequestSupport.MAX_SIZE)),
                "blogs", BlogFacetResponse.class).getMappedResults();
    }

    @Override
    public List<BlogFacetResponse> publicTags() {
        return mongoTemplate.aggregate(Aggregation.newAggregation(
                        Aggregation.match(publicCriteria()),
                        Aggregation.unwind("tags"),
                        Aggregation.group("tags").first("tags").as("name").first("tags").as("slug"),
                        Aggregation.sort(Sort.Direction.ASC, "name"),
                        Aggregation.limit(PageRequestSupport.MAX_SIZE)),
                "blogs", BlogFacetResponse.class).getMappedResults();
    }

    @Override
    public void publishDueScheduledBlogs() {
        Instant now = Instant.now();
        Pageable batch = PageRequestSupport.create(0, PageRequestSupport.MAX_SIZE,
                Sort.by(Sort.Direction.ASC, "scheduledAt"));
        for (Blog blog : blogRepository.findByStatusAndScheduledAtLessThanEqualAndDeletedAtIsNull(
                BlogStatus.SCHEDULED, now, batch)) {
            try {
                validatePublishGate(blog);
                blog.setStatus(BlogStatus.PUBLISHED);
                blog.setScheduledAt(null);
                if (blog.getPublishedAt() == null) blog.setPublishedAt(now);
                blog.setPublishedBy(blog.getUpdatedBy());
                if (blog.getSeo() != null) blog.getSeo().setNoIndex(false);
                blog.setUpdatedAt(now);
                blogRepository.save(blog);
            } catch (RuntimeException exception) {
                // Keep the scheduled record for inspection/retry rather than silently publishing invalid content.
                org.slf4j.LoggerFactory.getLogger(BlogServiceImpl.class)
                        .error("Could not publish scheduled blog [{}]", blog.getId(), exception);
            }
        }
    }

    private Blog findAdminBlog(String id) {
        if (!ObjectId.isValid(id)) {
            throw new AppException("Blog id must be a valid MongoDB ObjectId", HttpStatus.BAD_REQUEST);
        }
        return blogRepository.findById(id)
                .filter(blog -> blog.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Blog", "id", id));
    }

    private void requirePublishableState(Blog blog) {
        if (blog.getStatus() != BlogStatus.DRAFT && blog.getStatus() != BlogStatus.SCHEDULED
                && blog.getStatus() != BlogStatus.UNPUBLISHED) {
            throw new AppException("Blog cannot transition to publish from its current status", HttpStatus.CONFLICT);
        }
    }

    private void validatePublishGate(Blog blog) {
        if (!hasText(blog.getTitle()) || !hasText(blog.getSlug()) || !hasText(blog.getExcerpt())) {
            throw new AppException("Title, slug, and excerpt are required before publication", HttpStatus.BAD_REQUEST);
        }
        if (blog.getCategory() == null || !hasText(blog.getCategory().getName()) || !hasText(blog.getCategory().getSlug())) {
            throw new AppException("A blog category is required before publication", HttpStatus.BAD_REQUEST);
        }
        if (blog.getContent() == null || blog.getWordCount() == null || blog.getWordCount() < 1) {
            throw new AppException("Non-empty structured content is required before publication", HttpStatus.BAD_REQUEST);
        }
        validateContent(blog.getContent().getBody());
        int currentWordCount = countWords(blog.getContent().getBody());
        if (currentWordCount < 1) {
            throw new AppException("Non-empty structured content is required before publication", HttpStatus.BAD_REQUEST);
        }
        blog.setWordCount(currentWordCount);
        blog.setReadingTimeMinutes(Math.max(1, (currentWordCount + WORDS_PER_MINUTE - 1) / WORDS_PER_MINUTE));
        if (blog.getFeaturedImage() == null) {
            throw new AppException("A valid featured image is required before publication", HttpStatus.BAD_REQUEST);
        }
        Blog.BlogSeo seo = blog.getSeo();
        if (seo == null) {
            seo = new Blog.BlogSeo();
            blog.setSeo(seo);
        }
        if (!hasText(seo.getMetaTitle())) seo.setMetaTitle(blog.getTitle());
        if (!hasText(seo.getMetaDescription())) seo.setMetaDescription(blog.getExcerpt());
        if (!hasText(seo.getMetaTitle()) || !hasText(seo.getMetaDescription())) {
            throw new AppException("SEO title and description are required before publication", HttpStatus.BAD_REQUEST);
        }
        if (seo.getMetaTitle().length() > 200 || seo.getMetaDescription().length() > 300) {
            throw new AppException("SEO title or description exceeds the supported length", HttpStatus.BAD_REQUEST);
        }
        validateOptionalHttps(seo.getCanonicalUrl(), "canonicalUrl");
        validateOptionalHttps(seo.getOgImageUrl(), "ogImageUrl");
    }

    private Blog.BlogCategory normalizeCategory(Blog.BlogCategory category) {
        if (category == null) return null;
        if (!hasText(category.getName())) throw new AppException("Category name cannot be blank", HttpStatus.BAD_REQUEST);
        String slug = normalizeSlug(hasText(category.getSlug()) ? category.getSlug() : category.getName());
        if (slug.isBlank()) throw new AppException("Category slug must be URL-safe", HttpStatus.BAD_REQUEST);
        return new Blog.BlogCategory(category.getName().trim(), slug);
    }

    private List<String> normalizeTags(List<String> input) {
        if (input == null) return new ArrayList<>();
        if (input.size() > 10) throw new AppException("A blog can have at most 10 tags", HttpStatus.BAD_REQUEST);
        Set<String> unique = new HashSet<>();
        List<String> result = new ArrayList<>();
        for (String raw : input) {
            if (!hasText(raw)) throw new AppException("Tags cannot be blank", HttpStatus.BAD_REQUEST);
            String value = normalizeTag(raw);
            if (value.isBlank() || !unique.add(value)) throw new AppException("Tags must be unique URL-safe values", HttpStatus.BAD_REQUEST);
            result.add(value);
        }
        return result;
    }

    private String normalizeTag(String tag) { return normalizeSlug(tag); }

    private Blog.FeaturedImage validateFeaturedImage(Blog.FeaturedImage image) {
        if (image == null) return null;
        requireHttps(image.getUrl(), "Featured image URL");
        if (!hasText(image.getAltText())) throw new AppException("Featured image altText is required", HttpStatus.BAD_REQUEST);
        if ((image.getWidth() != null && (image.getWidth() < 1 || image.getWidth() > 10000))
                || (image.getHeight() != null && (image.getHeight() < 1 || image.getHeight() > 10000))
                || (image.getSizeBytes() != null && (image.getSizeBytes() < 1 || image.getSizeBytes() > 20_000_000))) {
            throw new AppException("Featured image width/height must be at most 10000 pixels and sizeBytes at most 20000000", HttpStatus.BAD_REQUEST);
        }
        if (hasText(image.getMimeType())
                && !Set.of("image/jpeg", "image/png", "image/webp", "image/avif")
                .contains(image.getMimeType().trim().toLowerCase(Locale.ROOT))) {
            throw new AppException("Featured image mimeType is not supported", HttpStatus.BAD_REQUEST);
        }
        if (hasText(image.getMimeType())) image.setMimeType(image.getMimeType().trim().toLowerCase(Locale.ROOT));
        return image;
    }

    private Blog.BlogSeo normalizeSeo(Blog.BlogSeo seo) {
        if (seo == null) return new Blog.BlogSeo();
        validateOptionalHttps(seo.getCanonicalUrl(), "canonicalUrl");
        validateOptionalHttps(seo.getOgImageUrl(), "ogImageUrl");
        return seo;
    }

    private void validateOptionalHttps(String value, String field) {
        if (hasText(value)) requireHttps(value, field);
    }

    private void requireHttps(String value, String field) {
        try {
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !hasText(uri.getHost())) throw new IllegalArgumentException();
        } catch (RuntimeException exception) {
            throw new AppException(field + " must be an absolute HTTPS URL", HttpStatus.BAD_REQUEST);
        }
    }

    private List<ObjectId> resolveRelatedBlogs(List<String> ids, String currentId) {
        if (ids == null) return new ArrayList<>();
        if (ids.size() > 8) throw new AppException("A blog can reference at most 8 related blogs", HttpStatus.BAD_REQUEST);
        Set<ObjectId> unique = new HashSet<>();
        List<ObjectId> result = new ArrayList<>();
        for (String id : ids) {
            if (!ObjectId.isValid(id)) throw new AppException("Related blog id is invalid", HttpStatus.BAD_REQUEST);
            ObjectId objectId = new ObjectId(id);
            if (objectId.toHexString().equalsIgnoreCase(currentId) || !unique.add(objectId)) {
                throw new AppException("Related blog references must be unique and cannot reference the current blog", HttpStatus.BAD_REQUEST);
            }
            if (blogRepository.findById(objectId.toHexString()).filter(blog -> blog.getDeletedAt() == null).isEmpty()) {
                throw new ResourceNotFoundException("Related blog", "id", id);
            }
            result.add(objectId);
        }
        return result;
    }

    private List<Blog.RelatedProduct> resolveRelatedProducts(List<String> ids) {
        if (ids == null) return new ArrayList<>();
        if (ids.size() > 10) throw new AppException("A blog can reference at most 10 related products", HttpStatus.BAD_REQUEST);
        Set<ObjectId> unique = new HashSet<>();
        List<Blog.RelatedProduct> result = new ArrayList<>();
        for (String id : ids) {
            if (!ObjectId.isValid(id)) throw new AppException("Related product id is invalid", HttpStatus.BAD_REQUEST);
            ObjectId objectId = new ObjectId(id);
            if (!unique.add(objectId)) throw new AppException("Related product ids must be unique", HttpStatus.BAD_REQUEST);
            Product product = productRepository.findById(objectId.toHexString())
                    .filter(this::isActiveProduct)
                    .orElseThrow(() -> new ResourceNotFoundException("Active product", "id", id));
            result.add(new Blog.RelatedProduct(objectId, product.getTitle()));
        }
        return result;
    }

    private boolean isActiveProduct(Product product) {
        return product.getStatus() == null || "ACTIVE".equalsIgnoreCase(product.getStatus())
                || "Active".equalsIgnoreCase(product.getStatus());
    }

    private void validateContent(Map<String, Object> body) {
        try {
            if (objectMapper.writeValueAsBytes(body).length > MAX_CONTENT_BYTES) {
                throw new AppException("Blog content exceeds the 1 MB limit", HttpStatus.BAD_REQUEST);
            }
        } catch (JsonProcessingException exception) {
            throw new AppException("Blog content is not valid JSON", HttpStatus.BAD_REQUEST);
        }
        Object type = body.get("type");
        if (!"doc".equals(type)) throw new AppException("TipTap content body must have type 'doc'", HttpStatus.BAD_REQUEST);
        int[] nodes = {0};
        validateNode(body, 0, nodes);
    }

    @SuppressWarnings("unchecked")
    private void validateNode(Object value, int depth, int[] nodes) {
        if (!(value instanceof Map<?, ?> rawNode)) throw new AppException("Blog content contains an invalid node", HttpStatus.BAD_REQUEST);
        if (depth > 32 || ++nodes[0] > 20_000) throw new AppException("Blog content structure is too complex", HttpStatus.BAD_REQUEST);
        Map<String, Object> node = (Map<String, Object>) rawNode;
        if (!Set.of("type", "attrs", "content", "text", "marks").containsAll(node.keySet())) {
            throw new AppException("Blog content node contains unsupported fields", HttpStatus.BAD_REQUEST);
        }
        Object typeValue = node.get("type");
        if (!(typeValue instanceof String type) || !NODE_TYPES.contains(type)) {
            throw new AppException("Blog content contains an unsupported node type", HttpStatus.BAD_REQUEST);
        }
        if ("text".equals(type) && !(node.get("text") instanceof String)) {
            throw new AppException("TipTap text nodes must include text", HttpStatus.BAD_REQUEST);
        }
        Object attrsValue = node.get("attrs");
        if (attrsValue != null) {
            if (!(attrsValue instanceof Map<?, ?> attrs)) throw new AppException("TipTap attrs must be an object", HttpStatus.BAD_REQUEST);
            if (attrs.keySet().stream().anyMatch(key -> !(key instanceof String name) || !NODE_ATTRIBUTES.contains(name))) {
                throw new AppException("TipTap attrs contain unsupported fields", HttpStatus.BAD_REQUEST);
            }
            Object href = attrs.get("href");
            if (href != null) validateLink(String.valueOf(href));
            Object src = attrs.get("src");
            if (src != null) requireHttps(String.valueOf(src), "Inline image URL");
            Object level = attrs.get("level");
            if (level != null && (!(level instanceof Number number) || (number.intValue() != 2 && number.intValue() != 3))) {
                throw new AppException("Blog headings may use only level 2 or 3", HttpStatus.BAD_REQUEST);
            }
        }
        Object marksValue = node.get("marks");
        if (marksValue != null) {
            if (!(marksValue instanceof List<?> marks)) throw new AppException("TipTap marks must be an array", HttpStatus.BAD_REQUEST);
            for (Object markValue : marks) {
                if (!(markValue instanceof Map<?, ?> mark) || !(mark.get("type") instanceof String markType)
                        || !MARK_TYPES.contains(markType)) {
                    throw new AppException("Blog content contains an unsupported text mark", HttpStatus.BAD_REQUEST);
                }
                if (mark.keySet().stream().anyMatch(key -> !(key instanceof String name)
                        || !(name.equals("type") || name.equals("attrs")))) {
                    throw new AppException("TipTap marks contain unsupported fields", HttpStatus.BAD_REQUEST);
                }
                if ("link".equals(markType) && mark.get("attrs") instanceof Map<?, ?> attrs && attrs.get("href") != null) {
                    validateLink(String.valueOf(attrs.get("href")));
                }
            }
        }
        Object contentValue = node.get("content");
        if (contentValue != null) {
            if (!(contentValue instanceof List<?> children)) throw new AppException("TipTap content must be an array", HttpStatus.BAD_REQUEST);
            for (Object child : children) validateNode(child, depth + 1, nodes);
        }
    }

    private void validateLink(String link) {
        try {
            URI uri = URI.create(link);
            String scheme = uri.getScheme();
            boolean webUrl = scheme != null && (scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
                    && hasText(uri.getHost());
            boolean email = scheme != null && scheme.equalsIgnoreCase("mailto")
                    && hasText(uri.getSchemeSpecificPart()) && !link.contains("\r") && !link.contains("\n");
            if (!webUrl && !email) throw new IllegalArgumentException();
        } catch (RuntimeException exception) {
            throw new AppException("Blog links must use HTTPS, HTTP, or mailto URLs", HttpStatus.BAD_REQUEST);
        }
    }

    @SuppressWarnings("unchecked")
    private int countWords(Object value) {
        StringBuilder text = new StringBuilder();
        collectText(value, text);
        Matcher matcher = Pattern.compile("[\\p{L}\\p{N}]+").matcher(text);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private void collectText(Object value, StringBuilder result) {
        if (value instanceof Map<?, ?> map) {
            Object text = map.get("text");
            if (text instanceof String string) result.append(string).append(' ');
            Object content = map.get("content");
            if (content instanceof List<?> list) list.forEach(item -> collectText(item, result));
        } else if (value instanceof List<?> list) {
            list.forEach(item -> collectText(item, result));
        }
    }

    private Criteria publicCriteria() {
        return new Criteria().andOperator(
                Criteria.where("status").is(BlogStatus.PUBLISHED),
                Criteria.where("deletedAt").is(null),
                Criteria.where("publishedAt").lte(Instant.now()));
    }

    private Pageable pageable(int page, int size, Sort sort) {
        return PageRequestSupport.create(page, size, sort);
    }

    private String normalizeSlug(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        return normalized.length() > 200 ? normalized.substring(0, 200).replaceAll("-$", "") : normalized;
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }

    private AppException duplicateSlug() {
        return new AppException("A blog with this slug already exists", HttpStatus.CONFLICT);
    }
}
