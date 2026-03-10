package com.bootcamp.project_stock_data.codelibrary;

import java.time.Duration;

import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import tools.jackson.databind.ObjectMapper;


public class RedisManager {

  private final RedisTemplate<String, String> redisTemplate;
  private final ObjectMapper objectMapper;

  // dependency = 呢個 class 要靠呢兩樣先做到「讀寫 redis + JSON 轉換」
  public RedisManager(ObjectMapper objectMapper, RedisConnectionFactory factory) {
    RedisTemplate<String, String> t = new RedisTemplate<>();
    t.setConnectionFactory(factory);

    // key/value 都存純 String（JSON string）
    t.setKeySerializer(new StringRedisSerializer());
    t.setValueSerializer(new StringRedisSerializer());
    t.setHashKeySerializer(new StringRedisSerializer());
    t.setHashValueSerializer(new StringRedisSerializer());

    t.afterPropertiesSet();

    this.redisTemplate = t;
    this.objectMapper = objectMapper;
  }

  // set with TTL
  public <T> void set(String key, T value, Duration ttl) {
    try {
      String json = this.objectMapper.writeValueAsString(value);
      if (ttl == null) {
        this.redisTemplate.opsForValue().set(key, json);
      } else {
        this.redisTemplate.opsForValue().set(key, json, ttl);
      }
    } catch (Exception e) {
      throw new RuntimeException("Redis set() JSON serialize failed, key=" + key, e);
    }
  }

  // set no TTL (persist until overwritten)
  public <T> void set(String key, T value) {
    set(key, value, null);
  }

  // get + deserialize
  public <T> T get(String key, Class<T> clazz) {
    String json = this.redisTemplate.opsForValue().get(key);
    if (json == null)
      return null;
    try {
      return this.objectMapper.readValue(json, clazz);
    } catch (Exception e) {
      throw new RuntimeException("Redis get() JSON deserialize failed, key=" + key, e);
    }
  }

  public Boolean delete(String key) {
    return this.redisTemplate.delete(key);
  }

  public Boolean exists(String key) {
    return this.redisTemplate.hasKey(key);
  }
}
