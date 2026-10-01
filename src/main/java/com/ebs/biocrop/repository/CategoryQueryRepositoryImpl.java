package com.ebs.biocrop.repository;

import com.ebs.biocrop.entity.Category;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/** Mongo query details for bounded category list endpoints. */
public class CategoryQueryRepositoryImpl implements CategoryQueryRepository {
    private static final int MAX_CHILDREN = 100;
    private final MongoTemplate mongoTemplate;

    public CategoryQueryRepositoryImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Page<Category> findPublicPage(String parentId, Integer level, Pageable pageable) {
        List<Criteria> filters = new ArrayList<>();
        filters.add(new Criteria().orOperator(Criteria.where("isActive").is(true), Criteria.where("isActive").is(null)));
        filters.add(Criteria.where("level").gte(0).lte(2));
        addOptionalFilters(filters, parentId, level);
        return findPage(filters, pageable);
    }

    @Override
    public Page<Category> findAdminPage(String parentId, Integer level, Pageable pageable) {
        List<Criteria> filters = new ArrayList<>();
        filters.add(Criteria.where("level").gte(0).lte(2));
        addOptionalFilters(filters, parentId, level);
        return findPage(filters, pageable);
    }

    @Override
    public List<Category> findChildren(String parentId, int limit) {
        Query query = Query.query(Criteria.where("parentId").is(parentId));
        query.limit(Math.max(1, Math.min(limit, MAX_CHILDREN)));
        return mongoTemplate.find(query, Category.class);
    }

    @Override
    public boolean hasActiveChildren(String parentId) {
        Query query = Query.query(new Criteria().andOperator(Criteria.where("parentId").is(parentId),
                new Criteria().orOperator(Criteria.where("isActive").is(true), Criteria.where("isActive").is(null))));
        query.limit(1);
        return mongoTemplate.exists(query, Category.class);
    }

    @Override
    public boolean existsSlugUnderParent(String parentId, String slug, String excludeId) {
        List<Criteria> filters = new ArrayList<>();
        filters.add(parentId == null ? Criteria.where("parentId").is(null) : Criteria.where("parentId").is(parentId));
        filters.add(Criteria.where("slug").is(slug));
        if (excludeId != null) filters.add(Criteria.where("_id").ne(excludeId));
        return mongoTemplate.exists(Query.query(new Criteria().andOperator(filters.toArray(Criteria[]::new))), Category.class);
    }

    private void addOptionalFilters(List<Criteria> filters, String parentId, Integer level) {
        if (StringUtils.hasText(parentId)) filters.add(Criteria.where("parentId").is(parentId.trim()));
        if (level != null) filters.add(Criteria.where("level").is(level));
    }

    private Page<Category> findPage(List<Criteria> filters, Pageable pageable) {
        Criteria combined = new Criteria().andOperator(filters.toArray(Criteria[]::new));
        Query query = Query.query(combined).with(pageable);
        long total = mongoTemplate.count(Query.query(combined), Category.class);
        return PageableExecutionUtils.getPage(mongoTemplate.find(query, Category.class), pageable, () -> total);
    }
}
