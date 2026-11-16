package com.ebs.biocrop.service;

import com.ebs.biocrop.entity.TechnicalName;

import java.util.List;
import java.util.Map;

public interface TechnicalNameService {

    List<Map<String, Object>> listPublic(String search, String category, String letter);

    List<Map<String, Object>> listForPanel(String search, String category, String letter, boolean activeOnly);

    Map<String, Object> create(String name, String category, Double commissionPercentage, Boolean isActive);

    Map<String, Object> update(String id, String name, String category, Double commissionPercentage, Boolean isActive, boolean syncProducts);

    void delete(String id);

    Map<String, Object> importFromCsv(String csvContent);

    TechnicalName findEntityById(String id);
}
