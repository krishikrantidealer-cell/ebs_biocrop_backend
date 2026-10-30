package com.ebs.biocrop.config;

import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

@Configuration
public class GcsConfig {

    @Bean
    public Storage storageClient(@Value("${google.application.credentials:${GOOGLE_APPLICATION_CREDENTIALS:}}") String credentialsPath) throws IOException {
        if (credentialsPath != null && !credentialsPath.isBlank()) {
            File file = new File(credentialsPath);
            if (file.exists()) {
                com.google.auth.oauth2.GoogleCredentials credentials =
                        com.google.auth.oauth2.GoogleCredentials.fromStream(new FileInputStream(file));
                return StorageOptions.newBuilder().setCredentials(credentials).build().getService();
            }
        }
        return StorageOptions.getDefaultInstance().getService();
    }
}