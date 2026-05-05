package com.logslim.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    public static final String TEMPLATE_CACHE = "templates";

    @Value("${logslim.template.max-count:100000}")
    private int maxTemplateCount;

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager(TEMPLATE_CACHE);
        manager.setCaffeine(Caffeine.newBuilder().maximumSize(maxTemplateCount));
        return manager;
    }
}
