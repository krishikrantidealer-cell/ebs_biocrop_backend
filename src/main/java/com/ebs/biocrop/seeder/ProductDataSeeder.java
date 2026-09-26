package com.ebs.biocrop.seeder;

import com.ebs.biocrop.entity.Category;
import com.ebs.biocrop.entity.Product;
import com.ebs.biocrop.entity.ProductVariant;
import com.ebs.biocrop.entity.enums.PaymentMethod;
import com.ebs.biocrop.entity.enums.ProductUnit;
import com.ebs.biocrop.entity.enums.ShippingMethod;
import com.ebs.biocrop.repository.CategoryRepository;
import com.ebs.biocrop.repository.ProductRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
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
@org.springframework.core.annotation.Order(2)
public class ProductDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductDataSeeder.class);

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ObjectMapper objectMapper;

    public ProductDataSeeder(ProductRepository productRepository, CategoryRepository categoryRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @org.springframework.beans.factory.annotation.Value("${app.seed-data:false}")
    private boolean seedData;

    @Override
    public void run(String... args) {
        if (!seedData) {
            log.info("Data Seeder is disabled via app.seed-data=false. Skipping product ingestion.");
            return;
        }

        long existingProducts = productRepository.count();
        if (existingProducts > 0) {
            log.error("Skipping product ingestion: {} products already exist. Use a reviewed migration/import process to update a non-empty catalog.", existingProducts);
            return;
        }

        Map<String, Category> categoriesByName = loadCategoriesByName();
        if (categoriesByName.isEmpty()) {
            throw new IllegalStateException("Cannot ingest products until categories have been seeded in seller_hub.categories.");
        }

        log.info("Starting Product Catalog Ingestion from Excel & JSON into MongoDB Atlas...");
        File excelFile = new File("resources/product.xlsx");
        File jsonFile = new File("resources/products_hierarchical.json");
        List<Product> products;

        if (excelFile.isFile()) {
            log.info("Found Excel file at: {}", excelFile.getAbsolutePath());
            products = parseProductsFromExcel(excelFile);
        } else if (jsonFile.isFile()) {
            log.info("Excel not found, using JSON fallback: {}", jsonFile.getAbsolutePath());
            try {
                products = new ArrayList<>(Arrays.asList(objectMapper.readValue(jsonFile, Product[].class)));
                for (Product product : products) {
                    if (product.getStatus() == null || product.getStatus().isBlank()) {
                        product.setStatus("ACTIVE");
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("Failed to read products from JSON: " + e.getMessage(), e);
            }
        } else {
            log.warn("Neither product.xlsx nor products_hierarchical.json found. Skipping product ingestion.");
            return;
        }

        if (products.isEmpty()) {
            log.warn("No products found to ingest.");
            return;
        }

        for (Product product : products) {
            linkCategoryReferences(product, categoriesByName);
        }

        log.info("Inserting {} products with resolved category references...", products.size());
        productRepository.saveAll(products);
        log.info("Product ingestion complete in seller_hub.products; collection count: {}", productRepository.count());
    }

    private Map<String, Category> loadCategoriesByName() {
        Map<String, Category> categoriesByName = new HashMap<>();
        for (Category category : categoryRepository.findAll()) {
            String key = normalizeName(category.getName());
            if (key.isBlank() || categoriesByName.putIfAbsent(key, category) != null) {
                throw new IllegalStateException("Category names must be present and unique before product ingestion.");
            }
        }
        return categoriesByName;
    }

    private void linkCategoryReferences(Product product, Map<String, Category> categoriesByName) {
        Category category = categoriesByName.get(normalizeName(product.getCategory()));
        if (category == null || category.getId() == null) {
            throw new IllegalStateException("No saved category matches product category: " + product.getCategory());
        }

        Category.SubCategory subCategory = category.getSubCategories().stream()
                .filter(candidate -> normalizeName(candidate.getName()).equals(normalizeName(product.getSubCategory())))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No subcategory '" + product.getSubCategory()
                        + "' exists under category '" + category.getName() + "'."));
        if (subCategory.getId() == null) {
            throw new IllegalStateException("Subcategory has no saved ID: " + subCategory.getName());
        }

        product.setCategoryId(category.getId());
        product.setSubCategoryId(subCategory.getId());
        product.setCategoryIds(List.of(category.getId()));
        product.setSubCategoryIds(List.of(subCategory.getId()));
    }

    private String normalizeName(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
    private List<Product> parseProductsFromExcel(File file) {
        Map<String, Product> productMap = new LinkedHashMap<>();

        try (InputStream is = new FileInputStream(file);
             Workbook workbook = new XSSFWorkbook(is)) {

            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                log.warn("Sheet at index 0 is empty");
                return new ArrayList<>();
            }

            Iterator<Row> rowIterator = sheet.iterator();
            if (!rowIterator.hasNext()) {
                return new ArrayList<>();
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

            while (rowIterator.hasNext()) {
                Row row = rowIterator.next();
                if (isRowEmpty(row)) {
                    continue;
                }

                try {
                    String name = getStringValue(row, colIndexMap, "name");
                    if (name == null || name.isBlank()) continue;
                    
                    Product product = productMap.get(name);
                    if (product == null) {
                        product = new Product();
                        product.setTitle(name);
                        product.setStatus(getStringValue(row, colIndexMap, "status"));
                        product.setProductCode(getStringValue(row, colIndexMap, "product code", "productcode", "product_code"));
                        
                        String statusStr = getStringValue(row, colIndexMap, "status");
                        product.setSourceStatus(statusStr);
                        
                        String inStockStr = getStringValue(row, colIndexMap, "in stock", "instock");
                        if (inStockStr != null) {
                            if (inStockStr.equalsIgnoreCase("yes") || inStockStr.equalsIgnoreCase("y") || inStockStr.equalsIgnoreCase("true") || inStockStr.equals("1")) {
                                product.setAvailabilityStatus("In Stock");
                            } else {
                                product.setAvailabilityStatus("Out of Stock");
                            }
                        } else {
                            product.setAvailabilityStatus("In Stock");
                        }
                        
                        String companyName = getStringValue(row, colIndexMap, "company");
                        product.setCompany(companyName);
                        product.setBrandName(companyName);
                        product.setVendor(companyName);
                        
                        product.setCategory(getStringValue(row, colIndexMap, "category"));
                        product.setSubCategory(getStringValue(row, colIndexMap, "sub category", "subcategory"));
                        String keywords = getStringValue(row, colIndexMap, "keywords", "keyword s", "keyword");
                        product.setKeywords(keywords);
                        if (keywords != null && !keywords.isBlank()) {
                            List<String> tags = Arrays.stream(keywords.split(","))
                                    .map(String::trim)
                                    .filter(s -> !s.isEmpty())
                                    .toList();
                            product.setTags(tags);
                        }
                        product.setGst(getIntegerValue(row, colIndexMap, "gst"));
                        product.setHsnCode(getStringValue(row, colIndexMap, "hsn code", "hsncode"));

                        String shippingStr = getStringValue(row, colIndexMap, "shipping through", "shippingthrough");
                        product.setShippingThrough(ShippingMethod.fromString(shippingStr));

                        String paymentStr = getStringValue(row, colIndexMap, "payment method", "paymentmethod");
                        product.setPaymentMethod(PaymentMethod.fromString(paymentStr));

                        product.setShippedBy(getStringValue(row, colIndexMap, "shipped by", "shippedby"));

                        productMap.put(name, product);
                    }

                    // Create Variant
                    ProductVariant variant = new ProductVariant();
                    variant.setVariationCode(getStringValue(row, colIndexMap, "variation code", "variationcode"));

                    String unitStr = getStringValue(row, colIndexMap, "unit");
                    variant.setUnit(ProductUnit.fromString(unitStr));

                    variant.setUnitQty(getIntegerValue(row, colIndexMap, "unit qty", "unitqty"));
                    
                    // Set size combining unitQty and unit
                    if (variant.getUnitQty() != null && variant.getUnit() != null) {
                        variant.setSize(variant.getUnitQty() + " " + variant.getUnit().name());
                    }

                    Double excelPrice = getDoubleValue(row, colIndexMap, "price");
                    Double excelSalePrice = getDoubleValue(row, colIndexMap, "sale price", "saleprice");
                    
                    variant.setSalePrice(excelSalePrice);
                    variant.setPrice(excelSalePrice != null ? excelSalePrice : excelPrice);
                    if (excelPrice != null) {
                        variant.setCompareAtPrice(excelPrice.intValue());
                    }

                    variant.setDiscountRs(getDoubleValue(row, colIndexMap, "discount rs.", "discount rs", "discountrs"));
                    
                    variant.setDiscountPercent(getDoubleValue(row, colIndexMap, "discount %", "discount percent", "discountpercent"));
                    variant.setCourierCharge(getDoubleValue(row, colIndexMap, "courier charge", "couriercharge"));
                    variant.setProductWeightGm(getDoubleValue(row, colIndexMap, "product weight(gm)", "product weight (gm)", "product weight", "weight"));
                    
                    // Set JSON weight
                    if (variant.getProductWeightGm() != null) {
                        variant.setWeight(variant.getProductWeightGm().intValue());
                    }
                    
                    variant.setStockQty(getIntegerValue(row, colIndexMap, "stock qty", "stockqty", "stock"));

                    variant.setMinOrderQty(getIntegerValue(row, colIndexMap, "min. order qty", "min order qty", "minorderqty"));
                    if (variant.getMinOrderQty() == null || variant.getMinOrderQty() <= 0) {
                        variant.setMinOrderQty(1);
                    }

                    variant.setSellerWillGet(getDoubleValue(row, colIndexMap, "seller will get", "sellerwillget"));

                    variant.setLength(getDoubleValue(row, colIndexMap, "length"));
                    variant.setWidth(getDoubleValue(row, colIndexMap, "width"));
                    variant.setHeight(getDoubleValue(row, colIndexMap, "height"));
                    variant.setDisplayOrder(getIntegerValue(row, colIndexMap, "display order", "displayorder"));
                    variant.setNotes(getStringValue(row, colIndexMap, "notes", "note"));

                    String isDefaultStr = getStringValue(row, colIndexMap, "is default", "isdefault", "default");
                    variant.setIsDefault(isDefaultStr != null && (isDefaultStr.equalsIgnoreCase("y") || isDefaultStr.equalsIgnoreCase("yes") || isDefaultStr.equalsIgnoreCase("true")));

                    if (variant.getVariationCode() != null && !variant.getVariationCode().isBlank()) {
                        product.getVariants().add(variant);
                    }
                    
                } catch (Exception e) {
                    log.warn("Error parsing row {}: {}", row.getRowNum() + 1, e.getMessage());
                }
            }

            // Compute minPrice and maxPrice for each product based on variant prices
            for (Product p : productMap.values()) {
                if (p.getVariants() != null && !p.getVariants().isEmpty()) {
                    int min = Integer.MAX_VALUE;
                    int max = Integer.MIN_VALUE;
                    for (ProductVariant v : p.getVariants()) {
                        if (v.getPrice() != null) {
                            int vp = v.getPrice().intValue();
                            if (vp < min) min = vp;
                            if (vp > max) max = vp;
                        }
                    }
                    if (min != Integer.MAX_VALUE) p.setMinPrice(min);
                    if (max != Integer.MIN_VALUE) p.setMaxPrice(max);
                }
            }

        } catch (Exception e) {
            log.error("Failed to parse Excel file: {}", e.getMessage(), e);
        }

        return new ArrayList<>(productMap.values());
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
