package com.ebs.biocrop.controller;

import com.ebs.biocrop.dto.response.ApiResponse;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.ProductVariant;
import com.ebs.biocrop.entity.VariantLabel;
import com.ebs.biocrop.repository.VariantLabelRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/variant-labels")
public class VariantLabelController {

    private static final Logger log = LoggerFactory.getLogger(VariantLabelController.class);
    private static final List<String> DEFAULT_LABELS = List.of(
            "50 ml", "100 ml", "250 ml", "500 ml", "1 Ltr", "5 Ltr",
            "100 gm", "250 gm", "500 gm", "1 kg", "5 kg", "10 kg", "25 kg", "50 kg",
            "1 Pack", "1 Piece", "1 Unit"
    );

    private final VariantLabelRepository repository;
    private final MongoTemplate mongoTemplate;

    public VariantLabelController(VariantLabelRepository repository, MongoTemplate mongoTemplate) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedIfEmpty() {
        try {
            if (repository.count() > 0) {
                return;
            }
            Map<String, String> unique = new LinkedHashMap<>();
            for (String label : DEFAULT_LABELS) {
                unique.putIfAbsent(normalize(label), label);
            }
            List<Product> products = mongoTemplate.findAll(Product.class);
            for (Product product : products) {
                if (product.getVariants() == null) continue;
                for (ProductVariant variant : product.getVariants()) {
                    if (variant.getLabel() == null) continue;
                    String trimmed = variant.getLabel().trim();
                    if (!trimmed.isEmpty()) {
                        unique.putIfAbsent(normalize(trimmed), trimmed);
                    }
                }
            }
            List<VariantLabel> toSave = new ArrayList<>();
            for (Map.Entry<String, String> entry : unique.entrySet()) {
                toSave.add(new VariantLabel(entry.getValue(), entry.getKey()));
            }
            repository.saveAll(toSave);
            log.info("[VARIANT-LABELS] Seeded {} variant labels", toSave.size());
        } catch (Exception ex) {
            log.warn("[VARIANT-LABELS] Failed to seed variant labels: {}", ex.getMessage());
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(
            @RequestParam(required = false) String search) {
        String q = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> result = new ArrayList<>();
        for (VariantLabel item : repository.findAllByOrderByLabelAsc()) {
            if (!q.isEmpty() && !item.getLabel().toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            result.add(toDto(item));
        }
        return ResponseEntity.ok(ApiResponse.ok("Variant labels fetched", result));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> create(
            @RequestBody Map<String, Object> body) {
        double packSize = parseDouble(body.get("packSize"));
        String unit = Objects.toString(body.get("unit"), "").trim();
        int packQuantity = parsePositiveInt(body.get("packQuantity"), 1);
        String raw = Objects.toString(body.get("label"), "").trim();
        if (raw.isEmpty() && packSize > 0 && !unit.isEmpty()) {
            raw = formatLabel(packSize, unit, packQuantity);
        }
        if (raw.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Variant label or pack size + unit is required"));
        }
        String normalized = normalize(raw);
        Optional<VariantLabel> existing = repository.findByNormalizedLabel(normalized);
        if (existing.isPresent()) {
            return ResponseEntity.ok(ApiResponse.ok("Variant label already exists", toDto(existing.get())));
        }
        VariantLabel saved = repository.save(new VariantLabel(raw, normalized, packSize, unit, packQuantity));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Variant label created", toDto(saved)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        Optional<VariantLabel> existingOpt = repository.findById(id);
        if (existingOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("Variant label not found"));
        }
        double packSize = parseDouble(body.get("packSize"));
        String unit = Objects.toString(body.get("unit"), "").trim();
        int packQuantity = parsePositiveInt(body.get("packQuantity"), 1);
        String raw = Objects.toString(body.get("label"), "").trim();
        if (raw.isEmpty() && packSize > 0 && !unit.isEmpty()) {
            raw = formatLabel(packSize, unit, packQuantity);
        }
        if (raw.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Variant label is required"));
        }
        String normalized = normalize(raw);
        Optional<VariantLabel> duplicate = repository.findByNormalizedLabel(normalized);
        if (duplicate.isPresent() && !duplicate.get().getId().equals(id)) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Variant label already exists"));
        }
        VariantLabel item = existingOpt.get();
        item.setLabel(raw);
        item.setNormalizedLabel(normalized);
        if (packSize > 0) item.setPackSize(packSize);
        if (!unit.isEmpty()) item.setUnit(unit);
        item.setPackQuantity(packQuantity);
        VariantLabel saved = repository.save(item);
        return ResponseEntity.ok(ApiResponse.ok("Variant label updated", toDto(saved)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String id) {
        if (!repository.existsById(id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("Variant label not found"));
        }
        repository.deleteById(id);
        return ResponseEntity.ok(ApiResponse.ok("Variant label deleted", null));
    }

    private static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static double parseDouble(Object value) {
        if (value == null) return 0.0;
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static int parsePositiveInt(Object value, int fallback) {
        if (value == null) return fallback;
        if (value instanceof Number n) return n.intValue() > 0 ? n.intValue() : fallback;
        try {
            int parsed = Integer.parseInt(value.toString().trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String formatNum(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return java.math.BigDecimal.valueOf(value)
                .setScale(3, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private static String formatLabel(double packSize, String unit, int packQuantity) {
        String sizeStr = formatNum(packSize);
        int qty = Math.max(packQuantity, 1);
        String cleanUnit = unit == null ? "" : unit.trim();
        String lowerUnit = cleanUnit.toLowerCase(Locale.ROOT);
        double rawTotal = packSize * qty;
        double displayTotal = rawTotal;
        String displayUnit = cleanUnit;
        if (rawTotal > 999) {
            if (lowerUnit.equals("g") || lowerUnit.equals("gm") || lowerUnit.equals("gram") || lowerUnit.equals("grams")) {
                displayTotal = rawTotal / 1000.0;
                displayUnit = "kg";
            } else if (lowerUnit.equals("ml")) {
                displayTotal = rawTotal / 1000.0;
                displayUnit = "Ltr";
            }
        }
        String totalStr = formatNum(displayTotal);
        return totalStr + " " + displayUnit + " (" + sizeStr + cleanUnit + " x " + qty + " Qty)";
    }

    private static Map<String, Object> toDto(VariantLabel item) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", item.getId());
        dto.put("label", item.getLabel());
        dto.put("packSize", item.getPackSize());
        dto.put("unit", item.getUnit() != null ? item.getUnit() : "");
        dto.put("packQuantity", item.getPackQuantity() > 0 ? item.getPackQuantity() : 1);
        return dto;
    }
}
