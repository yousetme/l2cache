package com.youset.l2cache.builder;

import com.youset.l2cache.CacheConfig;
import com.youset.l2cache.CacheSpec;
import com.youset.l2cache.cache.RedissonRBucketCache;
import com.youset.l2cache.content.CacheSupport;
import com.youset.l2cache.content.RedissonSupport;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author chenck
 * @date 2020/7/2 9:37
 */
public class RedisCacheBuilder extends AbstractCacheBuilder<RedissonRBucketCache> {

    private static final Logger logger = LoggerFactory.getLogger(RedisCacheBuilder.class);

    @Override
    public RedissonRBucketCache build(String cacheName) {

        RedissonClient redissonClient = this.getRedissonClient(this.getCacheConfig());

        return this.buildActualCache(cacheName, this.getCacheConfig(), redissonClient);
    }

    /**
     * 获取 RedissonClient 实例
     * 注：主要目的是适配基于setActualCacheClient()扩展点设置的 RedissonClient，避免重复创建 RedissonClient
     */
    protected RedissonClient getRedissonClient(CacheConfig cacheConfig) {
        Object actualCacheClient = this.getActualCacheClient();
        if (null != actualCacheClient && actualCacheClient instanceof RedissonClient) {
            logger.info("multiplexing RedissonClient instance");
            return (RedissonClient) actualCacheClient;
        }

        logger.info("get or create RedissonClient instance by cache config");
        return RedissonSupport.getRedisson(cacheConfig);
    }


    protected RedissonRBucketCache buildActualCache(String cacheName, CacheConfig cacheConfig, RedissonClient redissonClient) {
        CacheConfig.Redis redis = this.getCacheConfig().getRedis();

        // 获取一级缓存对应CacheSpec
        // 二级缓存的过期时间和最大缓存数量从一级缓存上取，保证一级缓存和二级缓存的配置一致
        CacheSpec cacheSpec = CacheSupport.getCacheSpec(cacheConfig.getComposite().getL1CacheType(), cacheName);
        if (null != cacheSpec) {
            // 覆盖CacheConfig.Redis的默认值
            if (cacheSpec.getExpireTime() < 0) {
                redis.getExpireTimeCacheNameMap().put(cacheName, 0L);// 0 表示无过期时间
            } else {
                redis.getExpireTimeCacheNameMap().put(cacheName, cacheSpec.getExpireTime());
            }
            logger.info("采用一级缓存上expireTime, 覆盖CacheConfig.Redis的默认值, cacheName={}, cacheSpec={}", cacheName, cacheSpec.toString());
        } else {
            logger.info("采用CacheConfig.Redis的值, cacheName={}, redis={}", cacheName, redis.toString());
        }
        logger.info("create a RedissonRBucketCache instance, cacheName={}", cacheName);
        return new RedissonRBucketCache(cacheName, cacheConfig, redissonClient);
    }
}
