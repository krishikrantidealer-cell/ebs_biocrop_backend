package com.ebs.biocrop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EbsBiocropWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(EbsBiocropWebApplication.class, args);
    }
}
