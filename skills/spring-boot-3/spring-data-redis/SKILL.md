---
name: spring-data-redis
description: >
  Use when implementing caching, session storage, rate limiting, or any Redis integration.
  Covers cache-aside pattern, key naming, TTL strategy, and serialization config.
---

# Spring Data Redis

## Dependencies

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-cache</artifactId>
</dependency>
```

## Configuration

```java
@Configuration
@EnableCaching
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(jsonSerializer()); // JSON, not Java serialization
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(jsonSerializer());
        return template;
    }

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(10))
            .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
            .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer()))
            .disableCachingNullValues();

        return RedisCacheManager.builder(factory)
            .cacheDefaults(config)
            .transactionAware()   // put/evict only after the surrounding transaction commits
            .withCacheConfiguration("orders", config.entryTtl(Duration.ofMinutes(5)))
            .withCacheConfiguration("products", config.entryTtl(Duration.ofHours(1)))
            .build();
    }

    // Records are final, so DefaultTyping.NON_FINAL writes no type id for them and every
    // cache read fails ("missing type id property '@class'"). The builder's defaultTyping(true)
    // types user classes too; the validator limits type ids to an allow-list instead of
    // LaissezFaire's "any class". BigDecimal is non-final, so java.math must be allowed.
    private GenericJackson2JsonRedisSerializer jsonSerializer() {
        ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .polymorphicTypeValidator(BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.example.")   // your DTO packages
                .allowIfSubType("java.util.")
                .allowIfSubType("java.math.")
                .allowIfSubType("java.time.")
                .build())
            .build();
        return GenericJackson2JsonRedisSerializer.builder()
            .objectMapper(mapper)
            .defaultTyping(true)
            .build();
    }
}
```

## Key Naming Convention

```
{app}:{domain}:{id}          → orders:order:uuid-here
{app}:{domain}:list:{filter} → orders:order:list:status:PENDING
{app}:session:{userId}       → orders:session:uuid-here
{app}:ratelimit:{ip}         → orders:ratelimit:192.168.1.1
```

## @Cacheable — Declarative Caching

```java
@Service
@RequiredArgsConstructor
public class ProductService {

    @Cacheable(value = "products", key = "#id")
    public ProductResponse findById(UUID id) {
        return productRepository.findById(id)
            .map(ProductResponse::from)
            .orElseThrow(() -> new EntityNotFoundException("Product not found: " + id));
    }

    @CachePut(value = "products", key = "#result.id")  // update cache after write
    @Transactional
    public ProductResponse update(UUID id, UpdateProductRequest request) {
        Product product = productRepository.findById(id).orElseThrow();
        product.update(request);
        return ProductResponse.from(productRepository.save(product));
    }

    @CacheEvict(value = "products", key = "#id")  // invalidate on delete
    @Transactional
    public void delete(UUID id) {
        productRepository.deleteById(id);
    }

    @CacheEvict(value = "products", allEntries = true)  // clear all
    public void clearCache() {}
}
```

## Manual Cache-Aside Pattern

```java
@Service
@RequiredArgsConstructor
public class OrderCacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private static final Duration TTL = Duration.ofMinutes(5);

    public Optional<OrderResponse> get(UUID orderId) {
        String key = "orders:order:" + orderId;
        Object cached = redisTemplate.opsForValue().get(key);
        if (cached == null) return Optional.empty();
        return Optional.of(objectMapper.convertValue(cached, OrderResponse.class));
    }

    public void put(OrderResponse order) {
        String key = "orders:order:" + order.id();
        redisTemplate.opsForValue().set(key, order, TTL);
    }

    public void evict(UUID orderId) {
        redisTemplate.delete("orders:order:" + orderId);
    }
}
```

## Rate Limiting with Redis

```java
@Component
@RequiredArgsConstructor
public class RateLimiter {

    private final RedisTemplate<String, String> redisTemplate;

    // INCR and PEXPIRE in ONE atomic script: as two separate commands, a crash or
    // timeout between them leaves a key with no TTL and locks the caller out forever.
    private static final RedisScript<Long> INCR_WITH_TTL = RedisScript.of(
        "local c = redis.call('INCR', KEYS[1]) " +
        "if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end " +
        "return c", Long.class);

    public boolean isAllowed(String identifier, int maxRequests, Duration window) {
        String key = "ratelimit:" + identifier;
        Long count = redisTemplate.execute(INCR_WITH_TTL, List.of(key), String.valueOf(window.toMillis()));
        return count != null && count <= maxRequests;
    }
}
```

## application.yml

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      password: ${REDIS_PASSWORD:}
      timeout: 2000ms
      lettuce:
        pool:
          max-active: 10
          max-idle: 5
          min-idle: 2
  cache:
    type: redis
```

## Cache Stampede

When a hot key expires, every concurrent request misses at once and they all hammer the DB to recompute
the same value (the "thundering herd"). For expensive, high-traffic loads, let one caller compute while
the rest wait:

```java
// sync = true — only one thread computes the value; others block on it
@Cacheable(value = "products", key = "#id", sync = true)
public ProductResponse findById(UUID id) { ... }
```

`sync = true` serializes recomputation per key within a single instance. For a fleet-wide guarantee,
add a short Redis lock (`SETNX` with a TTL) around the recompute. Pair with jittered TTLs so a batch of
keys written together doesn't all expire on the same second.

## Gotchas
- Agent combines `@Transactional` with `@CachePut`/`@CacheEvict` on a non-transaction-aware cache manager — the cache can be updated before commit (or evicted then re-filled with stale data); build it with `.transactionAware()`
- Agent implements a rate limiter as separate `INCR` then `EXPIRE` calls — a failure between them leaves a TTL-less key that blocks the caller forever; use one Lua script
- Agent allow-lists only `java.util.` for default typing — `BigDecimal` is non-final and carries a type id, so cached prices/totals fail to deserialize; allow `java.math.` (and `java.time.`) too
- Agent uses Java serialization for values — always use JSON (`GenericJackson2JsonRedisSerializer`)
- Agent uses `new GenericJackson2JsonRedisSerializer()` — its plain mapper has no JSR-310 module, so any `Instant`/`LocalDate` field fails to serialize; build it with `JavaTimeModule`
- Agent caches entities with JPA lazy fields — cache DTOs/response objects, not entities
- Agent uses no TTL — always set expiry, memory is not infinite
- Agent forgets `@EnableCaching` — `@Cacheable` silently does nothing without it
- Agent caches `null` values — use `.disableCachingNullValues()` to avoid storing misses
- Agent leaves hot keys unprotected — use `@Cacheable(sync = true)` to prevent stampede on expiry
- Agent gives every entry the same TTL — add jitter so keys don't expire in a synchronized wave
