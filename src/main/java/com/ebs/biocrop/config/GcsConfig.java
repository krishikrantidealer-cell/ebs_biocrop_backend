package com.ebs.biocrop.config;

import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class GcsConfig {

    @Bean
    public Storage storageClient() {
        return StorageOptions.getDefaultInstance().getService();
    }
}