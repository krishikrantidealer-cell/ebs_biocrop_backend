package com.ebs.biocrop.seeder;

import com.ebs.biocrop.entity.Category;
import com.ebs.biocrop.repository.CategoryRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.*;

@Component
@org.springframework.core.annotation.Order(1)
public class CategoryDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CategoryDataSeeder.class);
    private final CategoryRepository categoryRepository;
    private final DataFormatter dataFormatter = new DataFormatter();

    @Value("${app.seed-categories:false}")
    private boolean seedCategories;

    public CategoryDataSeeder(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    @Override
    public void run(String... args) {
        if (!seedCategories) {
            log.info("Category seeding is disabled via app.seed-categories=false.");
            return;
        }

        long existingCount = categoryRepository.count();
        if (existingCount > 0) {
            log.warn("Skipping category seeding: categories collection already contains {} documents.", existingCount);
            return;
        }

        File excelFile = new File("resources/product.xlsx");
        if (!excelFile.isFile()) {
            log.error("Cannot seed categories: workbook not found at {}.", excelFile.getAbsolutePath());
            return;
        }

        List<Category> categories = readCategories(excelFile);
        if (categories.isEmpty()) {
            log.error("No category hierarchy found in workbook; categories collection was not modified.");
            return;
        }

        categoryRepository.saveAll(categories);
        log.info("Seeded {} categories into seller_hub.categories.", categories.size());
    }

    private List<Category> readCategories(File file) {
        Map<String, Category> categories = new LinkedHashMap<>();
        try (InputStream input = new FileInputStream(file); Workbook workbook = new XSSFWorkbook(input)) {
            if (workbook.getNumberOfSheets() == 0) return List.of();
            Sheet sheet = workbook.getSheetAt(0);
            Iterator<Row> rows = sheet.iterator();
            if (!rows.hasNext()) return List.of();

            Map<String, Integer> columns = new HashMap<>();
            for (Cell cell : rows.next()) {
                columns.put(normalize(value(cell)), cell.getColumnIndex());
            }
            Integer categoryColumn = columns.get("category");
            Integer subCategoryColumn = columns.get("sub category");
            if (categoryColumn == null || subCategoryColumn == null) {
                log.error("Workbook must contain Category and Sub Category columns.");
                return List.of();
            }

            while (rows.hasNext()) {
                Row row = rows.next();
                String categoryName = value(row.getCell(categoryColumn)).trim();
                if (categoryName.isBlank()) continue;
                Category category = categories.computeIfAbsent(categoryName.toLowerCase(Locale.ROOT), key -> {
                    Category created = new Category();
                    created.setName(categoryName);
                    created.setBannerTitle(categoryName);
                    return created;
                });

                String subCategoryName = value(row.getCell(subCategoryColumn)).trim();
                if (subCategoryName.isBlank()) continue;
                Category.SubCategory subCategory = category.getSubCategories().stream()
                        .filter(item -> item.getName().equalsIgnoreCase(subCategoryName))
                        .findFirst().orElseGet(() -> {
                            Category.SubCategory created = new Category.SubCategory(subCategoryName);
                            category.getSubCategories().add(created);
                            return created;
                        });
            }
        } catch (Exception e) {
            log.error("Could not parse category hierarchy from {}: {}", file.getAbsolutePath(), e.getMessage(), e);
            return List.of();
        }
        return new ArrayList<>(categories.values());
    }

    private String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String value(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toString()
                    : dataFormatter.formatCellValue(cell);
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            case FORMULA -> dataFormatter.formatCellValue(cell);
            default -> "";
        };
    }
}