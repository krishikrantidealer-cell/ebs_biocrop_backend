package com.ebs.biocrop.seeder;

import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.enums.PaymentMethod;
import com.ebs.biocrop.entity.enums.ProductStatus;
import com.ebs.biocrop.entity.enums.ProductUnit;
import com.ebs.biocrop.entity.enums.ShippingMethod;
import com.ebs.biocrop.repository.ProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.*;

@Component
public class ProductDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductDataSeeder.class);

    private final ProductRepository productRepository;
    private final ObjectMapper objectMapper;

    public ProductDataSeeder(ProductRepository productRepository) {
        this.productRepository = productRepository;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public void run(String... args) {
        log.info("Starting Product Catalog Ingestion from Excel & JSON into MongoDB Atlas...");

        File excelFile = new File("resources/product.xlsx");
        if (!excelFile.exists()) {
            excelFile = new File("C:/Users/essen/IdeaProjects/ebs_biocrop_web/resources/product.xlsx");
        }

        File jsonFile = new File("resources/products.json");
        if (!jsonFile.getParentFile().exists()) {
            jsonFile.getParentFile().mkdirs();
        }

        List<Product> products = Collections.emptyList();

        if (excelFile.exists()) {
            log.info("Found Excel file at: {}", excelFile.getAbsolutePath());
            products = parseProductsFromExcel(excelFile);
            log.info("Successfully parsed {} products from Excel.", products.size());

            // Save all parsed products as JSON for quick access and persistence if not already present
            if (!jsonFile.exists()) {
                try {
                    objectMapper.writeValue(jsonFile, products);
                    log.info("Successfully exported {} products to JSON: {}", products.size(), jsonFile.getAbsolutePath());
                } catch (Exception e) {
                    log.warn("Could not save products.json file: {}", e.getMessage());
                }
            }
        } else if (jsonFile.exists()) {
            log.info("Excel not found, fallback to existing JSON file: {}", jsonFile.getAbsolutePath());
            try {
                products = Arrays.asList(objectMapper.readValue(jsonFile, Product[].class));
            } catch (Exception e) {
                log.error("Failed to read products from JSON: {}", e.getMessage());
            }
        } else {
            log.warn("Neither product.xlsx nor products.json found. Skipping product ingestion.");
            return;
        }

        if (products.isEmpty()) {
            log.warn("No products found to ingest.");
            return;
        }

        long existingCount = productRepository.count();
        if (existingCount >= products.size()) {
            log.info("=========================================================================");
            log.info("✅ CATALOG ALREADY FULLY SEEDED IN MONGODB ATLAS (seller_hub.products)");
            log.info("📊 Current Collection Count: {} (Expected: {}). Skipping redundant re-ingestion.", existingCount, products.size());
            log.info("=========================================================================");
            return;
        }

        // Ingest / Upsert products into MongoDB Atlas (seller_hub.products)
        List<Product> existingProducts = productRepository.findAll();
        Map<String, Product> existingMap = new HashMap<>();
        for (Product ep : existingProducts) {
            if (ep.getVariationCode() != null) {
                existingMap.put(ep.getVariationCode(), ep);
            }
        }

        List<Product> toSave = new ArrayList<>();
        int updatedCount = 0;
        int insertedCount = 0;

        for (Product product : products) {
            if (product.getVariationCode() == null || product.getVariationCode().isBlank()) {
                continue;
            }

            Product existing = existingMap.get(product.getVariationCode());
            if (existing != null) {
                product.setId(existing.getId());
                product.setCreatedAt(existing.getCreatedAt() != null ? existing.getCreatedAt() : LocalDateTime.now());
                product.setUpdatedAt(LocalDateTime.now());
                updatedCount++;
            } else {
                product.setCreatedAt(LocalDateTime.now());
                product.setUpdatedAt(LocalDateTime.now());
                insertedCount++;
            }
            toSave.add(product);
        }

        if (!toSave.isEmpty()) {
            productRepository.saveAll(toSave);
        }

        long totalCount = productRepository.count();
        log.info("=========================================================================");
        log.info("✅ PRODUCT INGESTION COMPLETE IN MONGODB ATLAS (seller_hub.products)");
        log.info("📊 Inserted: {}, Updated: {}, Total Collection Count: {}", insertedCount, updatedCount, totalCount);
        log.info("=========================================================================");
    }

    private List<Product> parseProductsFromExcel(File file) {
        List<Product> productList = new ArrayList<>();

        try (InputStream is = new FileInputStream(file);
             Workbook workbook = new XSSFWorkbook(is)) {

            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                log.warn("Sheet at index 0 is empty");
                return productList;
            }

            Iterator<Row> rowIterator = sheet.iterator();
            if (!rowIterator.hasNext()) {
                return productList;
            }

            // Read header row
            Row headerRow = rowIterator.next();
            Map<String, Integer> colIndexMap = new HashMap<>();
            for (Cell cell : headerRow) {
                String header = getCellValueAsString(cell).trim().toLowerCase();
                // Normalize spaces and special chars
                header = header.replaceAll("\\s+", " ");
                colIndexMap.put(header, cell.getColumnIndex());
            }

            log.info("Identified {} columns in Excel header: {}", colIndexMap.size(), colIndexMap.keySet());

            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();
                if (isRowEmpty(row)) {
                    continue;
                }

                try {
                    Product p = mapRowToProduct(row, colIndexMap);
                    if (p.getVariationCode() != null && !p.getVariationCode().isBlank()) {
                        productList.add(p);
                    }
                } catch (Exception e) {
                    log.warn("Error parsing row {}: {}", row.getRowNum() + 1, e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("Failed to parse Excel file: {}", e.getMessage(), e);
        }

        return productList;
    }

    private Product mapRowToProduct(Row row, Map<String, Integer> colMap) {
        Product p = new Product();

        p.setName(getStringValue(row, colMap, "name"));
        p.setProductCode(getStringValue(row, colMap, "product code", "productcode", "product_code"));
        
        String statusStr = getStringValue(row, colMap, "status");
        p.setStatus(ProductStatus.fromString(statusStr));

        p.setCompany(getStringValue(row, colMap, "company"));
        p.setCategory(getStringValue(row, colMap, "category"));
        p.setSubCategory(getStringValue(row, colMap, "sub category", "subcategory"));
        p.setSubSubCategory(getStringValue(row, colMap, "sub sub category", "subsubcategory"));
        p.setKeywords(getStringValue(row, colMap, "keywords", "keyword s", "keyword"));

        p.setGst(getIntegerValue(row, colMap, "gst"));
        p.setHsnCode(getStringValue(row, colMap, "hsn code", "hsncode"));
        p.setVariationCode(getStringValue(row, colMap, "variation code", "variationcode"));

        String unitStr = getStringValue(row, colMap, "unit");
        p.setUnit(ProductUnit.fromString(unitStr));

        p.setUnitQty(getIntegerValue(row, colMap, "unit qty", "unitqty"));
        p.setPrice(getDoubleValue(row, colMap, "price"));
        p.setDiscountRs(getDoubleValue(row, colMap, "discount rs.", "discount rs", "discountrs"));
        p.setSalePrice(getDoubleValue(row, colMap, "sale price", "saleprice"));
        p.setDiscountPercent(getDoubleValue(row, colMap, "discount %", "discount percent", "discountpercent"));
        p.setCourierCharge(getDoubleValue(row, colMap, "courier charge", "couriercharge"));
        p.setProductWeightGm(getDoubleValue(row, colMap, "product weight(gm)", "product weight (gm)", "product weight", "weight"));
        p.setStockQty(getIntegerValue(row, colMap, "stock qty", "stockqty", "stock"));

        String shippingStr = getStringValue(row, colMap, "shipping through", "shippingthrough");
        p.setShippingThrough(ShippingMethod.fromString(shippingStr));

        String paymentStr = getStringValue(row, colMap, "payment method", "paymentmethod");
        p.setPaymentMethod(PaymentMethod.fromString(paymentStr));

        String inStockStr = getStringValue(row, colMap, "in stock", "instock");
        p.setInStock(inStockStr == null || inStockStr.equalsIgnoreCase("yes") || inStockStr.equalsIgnoreCase("y") || inStockStr.equalsIgnoreCase("true"));

        p.setMinOrderQty(getIntegerValue(row, colMap, "min. order qty", "min order qty", "minorderqty"));
        if (p.getMinOrderQty() == null || p.getMinOrderQty() <= 0) {
            p.setMinOrderQty(1);
        }

        p.setShippedBy(getStringValue(row, colMap, "shipped by", "shippedby"));
        p.setSellerWillGet(getDoubleValue(row, colMap, "seller will get", "sellerwillget"));

        p.setLength(getDoubleValue(row, colMap, "length"));
        p.setWidth(getDoubleValue(row, colMap, "width"));
        p.setHeight(getDoubleValue(row, colMap, "height"));

        p.setDisplayOrder(getIntegerValue(row, colMap, "display order", "displayorder"));
        p.setNotes(getStringValue(row, colMap, "notes", "note"));

        String isDefaultStr = getStringValue(row, colMap, "is default", "isdefault", "default");
        p.setIsDefault(isDefaultStr != null && (isDefaultStr.equalsIgnoreCase("y") || isDefaultStr.equalsIgnoreCase("yes") || isDefaultStr.equalsIgnoreCase("true")));

        return p;
    }

    private String getStringValue(Row row, Map<String, Integer> colMap, String... keys) {
        Integer colIdx = findColumnIndex(colMap, keys);
        if (colIdx == null) return null;
        Cell cell = row.getCell(colIdx);
        String val = getCellValueAsString(cell);
        return (val != null && !val.isBlank()) ? val.trim() : null;
    }

    private Double getDoubleValue(Row row, Map<String, Integer> colMap, String... keys) {
        Integer colIdx = findColumnIndex(colMap, keys);
        if (colIdx == null) return null;
        Cell cell = row.getCell(colIdx);
        if (cell == null) return null;

        if (cell.getCellType() == CellType.NUMERIC) {
            return cell.getNumericCellValue();
        }
        String str = getCellValueAsString(cell);
        if (str == null || str.isBlank()) return null;
        try {
            return Double.parseDouble(str.trim().replaceAll("[^0-9.]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer getIntegerValue(Row row, Map<String, Integer> colMap, String... keys) {
        Double d = getDoubleValue(row, colMap, keys);
        return d != null ? d.intValue() : null;
    }

    private Integer findColumnIndex(Map<String, Integer> colMap, String... keys) {
        for (String k : keys) {
            Integer idx = colMap.get(k.toLowerCase());
            if (idx != null) return idx;
        }
        return null;
    }

    private String getCellValueAsString(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toString();
                }
                double num = cell.getNumericCellValue();
                if (num == Math.floor(num)) {
                    yield String.valueOf((long) num);
                }
                yield String.valueOf(num);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield String.valueOf(cell.getNumericCellValue());
                } catch (Exception e) {
                    yield cell.getStringCellValue();
                }
            }
            default -> "";
        };
    }

    private boolean isRowEmpty(Row row) {
        if (row == null) return true;
        for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
            Cell cell = row.getCell(c);
            if (cell != null && cell.getCellType() != CellType.BLANK && !getCellValueAsString(cell).isBlank()) {
                return false;
            }
        }
        return true;
    }
}
