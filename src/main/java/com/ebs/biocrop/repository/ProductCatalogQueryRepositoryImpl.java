package com.ebs.biocrop.repository;

import com.ebs.biocrop.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.support.PageableExecutionUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/** Encapsulates MongoDB-specific public catalog search and paging queries. */
public class ProductCatalogQueryRepositoryImpl implements ProductCatalogQueryRepository {
    private final MongoTemplate mongoTemplate;

    public ProductCatalogQueryRepositoryImpl(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Override
    public Page<Product> findPublicPage(Pageable pageable) {
        return findPage(publicCriteria(), pageable);
    }

    @Override
    public Page<Product> findPublicByCategoryIds(Collection<String> categoryIds, Pageable pageable) {
        List<Criteria> filters = new ArrayList<>();
        filters.add(publicCriteria());
        filters.add(Criteria.where("categoryId").in(categoryIds));
        return findPage(new Criteria().andOperator(filters.toArray(Criteria[]::new)), pageable);
    }

    @Override
    public Page<Product> searchPublic(String searchText, Pageable pageable) {
        String safePattern = Pattern.quote(searchText);
        Criteria text = new Criteria().orOperator(
                Criteria.where("title").regex(safePattern, "i"),
                Criteria.where("description").regex(safePattern, "i"),
                Criteria.where("technicalName").regex(safePattern, "i"),
                Criteria.where("vendor").regex(safePattern, "i"),
                Criteria.where("technicalContent").regex(safePattern, "i"));
        return findPage(new Criteria().andOperator(publicCriteria(), text), pageable);
    }

    private Criteria publicCriteria() {
        return new Criteria().andOperator(
                Criteria.where("status").regex("^ACTIVE$", "i"),
                Criteria.where("sellerId").exists(true).nin(null, ""),
                Criteria.where("isAvailable").is(true));
    }

    private Page<Product> findPage(Criteria criteria, Pageable pageable) {
        Query query = Query.query(criteria).with(pageable);
        long total = mongoTemplate.count(Query.query(criteria), Product.class);
        return PageableExecutionUtils.getPage(mongoTemplate.find(query, Product.class), pageable, () -> total);
    }
}
