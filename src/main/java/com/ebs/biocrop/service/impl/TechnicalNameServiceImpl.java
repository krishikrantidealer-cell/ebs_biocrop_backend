package com.ebs.biocrop.service.impl;

import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.ProductVariant;
import com.ebs.biocrop.entity.TechnicalName;
import com.ebs.biocrop.exception.AppException;
import com.ebs.biocrop.exception.ResourceNotFoundException;
import com.ebs.biocrop.repository.TechnicalNameRepository;
import com.ebs.biocrop.service.RedisJsonCache;
import com.ebs.biocrop.service.TechnicalNameService;
import com.ebs.biocrop.websocket.WebSocketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class TechnicalNameServiceImpl implements TechnicalNameService {

    private static final Logger log = LoggerFactory.getLogger(TechnicalNameServiceImpl.class);

    private final TechnicalNameRepository repository;
    private final MongoTemplate mongoTemplate;
    private final RedisJsonCache redisCache;
    private final WebSocketService webSocketService;

    public TechnicalNameServiceImpl(TechnicalNameRepository repository, MongoTemplate mongoTemplate) {
        this(repository, mongoTemplate, null, null);
    }

    @Autowired
    public TechnicalNameServiceImpl(
            TechnicalNameRepository repository,
            MongoTemplate mongoTemplate,
            @Autowired(required = false) RedisJsonCache redisCache,
            @Autowired(required = false) WebSocketService webSocketService) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
        this.redisCache = redisCache;
        this.webSocketService = webSocketService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedIfEmpty() {
        try {
            if (repository.count() > 0) {
                return;
            }
            ClassPathResource resource = new ClassPathResource("seed/technical_names.csv");
            if (!resource.exists()) {
                log.warn("[TECHNICAL-NAMES] Seed file seed/technical_names.csv not found");
                return;
            }
            String csv = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> result = importFromCsv(csv);
            log.info("[TECHNICAL-NAMES] Seeded initial catalog: {}", result);
        } catch (Exception ex) {
            log.warn("[TECHNICAL-NAMES] Failed to seed initial technical names: {}", ex.getMessage());
        }
    }

    @Override
    public List<Map<String, Object>> listPublic(String search, String category, String letter) {
        return filterAndMap(repository.findByIsActiveTrueOrderByNameAsc(), search, category, letter, false);
    }

    @Override
    public List<Map<String, Object>> listForPanel(String search, String category, String letter, boolean activeOnly) {
        List<TechnicalName> source = activeOnly
                ? repository.findByIsActiveTrueOrderByNameAsc()
                : repository.findAllByOrderByNameAsc();
        return filterAndMap(source, search, category, letter, true);
    }

    @Override
    public Map<String, Object> create(String name, String category, Double commissionPercentage, Boolean isActive) {
        String cleanName = requireCleanName(name);
        String normalized = normalize(cleanName);
        if (repository.findByNormalizedName(normalized).isPresent()) {
            throw new AppException("Technical name already exists: " + cleanName, HttpStatus.CONFLICT);
        }

        Instant now = Instant.now();
        TechnicalName entity = new TechnicalName();
        entity.setName(cleanName);
        entity.setNormalizedName(normalized);
        entity.setLetter(extractLetter(cleanName));
        entity.setCategory(cleanCategory(category));
        entity.setCommissionPercentage(sanitizeCommission(commissionPercentage));
        entity.setIsActive(isActive == null || isActive);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);

        TechnicalName saved = repository.save(entity);
        invalidateAndBroadcast("technical-names:update", saved);
        return toMap(saved, true);
    }

    @Override
    public Map<String, Object> update(
            String id,
            String name,
            String category,
            Double commissionPercentage,
            Boolean isActive,
            boolean syncProducts) {
        TechnicalName entity = findEntityById(id);
        String previousName = entity.getName();

        if (name != null && !name.isBlank()) {
            String cleanName = requireCleanName(name);
            String normalized = normalize(cleanName);
            repository.findByNormalizedName(normalized).ifPresent(other -> {
                if (!other.getId().equals(entity.getId())) {
                    throw new AppException("Technical name already exists: " + cleanName, HttpStatus.CONFLICT);
                }
            });
            entity.setName(cleanName);
            entity.setNormalizedName(normalized);
            entity.setLetter(extractLetter(cleanName));
        }

        if (category != null) {
            entity.setCategory(cleanCategory(category));
        }
        if (commissionPercentage != null) {
            entity.setCommissionPercentage(sanitizeCommission(commissionPercentage));
        }
        if (isActive != null) {
            entity.setIsActive(isActive);
        }
        entity.setUpdatedAt(Instant.now());

        TechnicalName saved = repository.save(entity);
        if (syncProducts) {
            syncMatchingProducts(previousName, saved.getName(), saved.getCommissionPercentage());
        }
        invalidateAndBroadcast("technical-names:update", saved);
        return toMap(saved, true);
    }

    @Override
    public void delete(String id) {
        TechnicalName entity = findEntityById(id);
        repository.delete(entity);
        invalidateAndBroadcast("technical-names:update", Map.of("id", id, "deleted", true));
    }

    @Override
    public Map<String, Object> importFromCsv(String csvContent) {
        if (csvContent == null || csvContent.isBlank()) {
            throw new AppException("CSV content is empty", HttpStatus.BAD_REQUEST);
        }

        int inserted = 0;
        int updated = 0;
        int skipped = 0;

        try (BufferedReader reader = new BufferedReader(new StringReader(csvContent))) {
            String line;
            boolean headerSkipped = false;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> cols = parseCsvLine(line);
                if (!headerSkipped) {
                    headerSkipped = true;
                    String firstCol = cols.isEmpty() ? "" : cols.get(0).trim().toLowerCase(Locale.ROOT);
                    if (firstCol.equals("#") || firstCol.equals("technical name") || firstCol.equals("name")) {
                        continue;
                    }
                }

                // Supports either 5-col sheet (#, Letter, Technical Name, Category, Commission)
                // or 3-col CSV (Technical Name, Category, Commission)
                String rawName;
                String rawCategory;
                String rawCommission;
                if (cols.size() >= 5) {
                    rawName = cols.get(2);
                    rawCategory = cols.get(3);
                    rawCommission = cols.get(4);
                } else if (cols.size() >= 3) {
                    rawName = cols.get(0);
                    rawCategory = cols.get(1);
                    rawCommission = cols.get(2);
                } else {
                    skipped++;
                    continue;
                }

                String cleanName = rawName == null ? "" : rawName.trim();
                if (cleanName.isEmpty()) {
                    skipped++;
                    continue;
                }

                String normalized = normalize(cleanName);
                double commission = parseCommission(rawCommission);
                String cat = cleanCategory(rawCategory);
                Instant now = Instant.now();

                TechnicalName existing = repository.findByNormalizedName(normalized).orElse(null);
                if (existing != null) {
                    existing.setName(cleanName);
                    existing.setLetter(extractLetter(cleanName));
                    existing.setCategory(cat);
                    existing.setCommissionPercentage(commission);
                    existing.setUpdatedAt(now);
                    repository.save(existing);
                    updated++;
                } else {
                    TechnicalName created = new TechnicalName();
                    created.setName(cleanName);
                    created.setNormalizedName(normalized);
                    created.setLetter(extractLetter(cleanName));
                    created.setCategory(cat);
                    created.setCommissionPercentage(commission);
                    created.setIsActive(true);
                    created.setCreatedAt(now);
                    created.setUpdatedAt(now);
                    repository.save(created);
                    inserted++;
                }
            }
        } catch (AppException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AppException("Failed to parse technical names CSV: " + ex.getMessage(), HttpStatus.BAD_REQUEST);
        }

        invalidateAndBroadcast("technical-names:update", Map.of("imported", inserted + updated));
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("inserted", inserted);
        summary.put("updated", updated);
        summary.put("skipped", skipped);
        summary.put("total", repository.count());
        return summary;
    }

    @Override
    public TechnicalName findEntityById(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("TechnicalName", "id", id));
    }

    private List<Map<String, Object>> filterAndMap(
            List<TechnicalName> source,
            String search,
            String category,
            String letter,
            boolean includeCommission) {
        String searchLower = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        String catLower = category == null ? "" : category.trim().toLowerCase(Locale.ROOT);
        String letterUpper = letter == null ? "" : letter.trim().toUpperCase(Locale.ROOT);

        List<Map<String, Object>> out = new ArrayList<>();
        for (TechnicalName item : source) {
            if (!searchLower.isEmpty()) {
                String n = item.getName() == null ? "" : item.getName().toLowerCase(Locale.ROOT);
                String c = item.getCategory() == null ? "" : item.getCategory().toLowerCase(Locale.ROOT);
                if (!n.contains(searchLower) && !c.contains(searchLower)) {
                    continue;
                }
            }
            if (!catLower.isEmpty() && !catLower.equals("all")) {
                String c = item.getCategory() == null ? "" : item.getCategory().toLowerCase(Locale.ROOT);
                if (!c.equals(catLower)) {
                    continue;
                }
            }
            if (!letterUpper.isEmpty() && !letterUpper.equals("ALL")) {
                String l = item.getLetter() == null ? "" : item.getLetter().toUpperCase(Locale.ROOT);
                if (!l.equals(letterUpper)) {
                    continue;
                }
            }
            out.add(toMap(item, includeCommission));
        }
        return out;
    }

    private Map<String, Object> toMap(TechnicalName item, boolean includeCommission) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", item.getId());
        m.put("name", item.getName());
        m.put("letter", item.getLetter());
        m.put("category", item.getCategory());
        m.put("isActive", !Boolean.FALSE.equals(item.getIsActive()));
        if (includeCommission) {
            m.put("commissionPercentage", item.getCommissionPercentage() != null ? item.getCommissionPercentage() : 0.0);
        }
        return m;
    }

    private void syncMatchingProducts(String oldName, String newName, Double commissionPercentage) {
        if (mongoTemplate == null || oldName == null || oldName.isBlank()) {
            return;
        }
        Pattern exactPattern = Pattern.compile("^" + Pattern.quote(oldName.trim()) + "$", Pattern.CASE_INSENSITIVE);
        List<Product> matching = mongoTemplate.find(
                Query.query(Criteria.where("technicalName").regex(exactPattern)),
                Product.class);
        for (Product product : matching) {
            if (newName != null && !newName.isBlank()) {
                product.setTechnicalName(newName);
            }
            if (commissionPercentage != null && product.getVariants() != null) {
                for (ProductVariant v : product.getVariants()) {
                    v.setCommissionPercentage(commissionPercentage);
                }
            }
            mongoTemplate.save(product);
        }
        if (!matching.isEmpty() && redisCache != null) {
            redisCache.invalidateRegion("public-products");
        }
    }

    private void invalidateAndBroadcast(String event, Object payload) {
        if (redisCache != null) {
            redisCache.invalidateRegion("technical-names");
        }
        if (webSocketService != null) {
            webSocketService.broadcast(event, payload);
        }
    }

    private static String requireCleanName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new AppException("Technical name is required", HttpStatus.BAD_REQUEST);
        }
        return name.trim();
    }

    private static String normalize(String name) {
        return name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String extractLetter(String name) {
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isLetter(ch)) {
                return String.valueOf(Character.toUpperCase(ch));
            }
        }
        return "#";
    }

    private static String cleanCategory(String category) {
        return category == null || category.trim().isEmpty() ? "General" : category.trim();
    }

    private static double sanitizeCommission(Double val) {
        if (val == null || val.isNaN() || val.isInfinite() || val < 0) {
            return 0.0;
        }
        return Math.min(100.0, Math.round(val * 100.0) / 100.0);
    }

    public static double parseCommission(String raw) {
        if (raw == null || raw.isBlank()) return 0.0;
        String cleaned = raw.replace("%", "").trim();
        try {
            return sanitizeCommission(Double.parseDouble(cleaned));
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    private static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString().trim());
        return fields;
    }
}
