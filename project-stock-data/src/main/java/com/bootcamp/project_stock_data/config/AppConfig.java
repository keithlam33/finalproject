package com.bootcamp.project_stock_data.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.web.client.RestTemplate;

import com.bootcamp.project_stock_data.codelibrary.RedisManager;

import tools.jackson.databind.ObjectMapper;

@Configuration
public class AppConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    RedisManager redisManager(ObjectMapper objectMapper, RedisConnectionFactory factory) {
        return new RedisManager(objectMapper, factory);
    }
}
