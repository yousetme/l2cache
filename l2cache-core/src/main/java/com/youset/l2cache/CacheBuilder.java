package com.youset.l2cache;

import com.youset.l2cache.cache.expire.CacheExpiredListener;
import com.youset.l2cache.spi.SPI;

/**
 * cache构建器
 *
 * @author chenck
 * @date 2020/7/1 20:43
 */
@SPI
public interface CacheBuilder<T extends Cache> {

    /**
     * 构建指定名称的cache对象
     */
    T build(String cacheName);

    /**
     * 解析缓存配置，主要针对一级缓存，如guava cache、caffeine等
     */
    CacheSpec parseSpec(String cacheName);

    /**
     * 复制属性
     */
    void copyFrom(CacheBuilder sourceBuilder);

    /**
     * 获取缓存配置
     */
    CacheConfig getCacheConfig();

    /**
     * 设置缓存配置
     */
    CacheBuilder setCacheConfig(CacheConfig cacheConfig);

    /**
     * 获取缓存过期监听器
     */
    CacheExpiredListener getExpiredListener();

    /**
     * 设置缓存过期监听器
     */
    CacheBuilder setExpiredListener(CacheExpiredListener expiredListener);

    /**
     * 获取缓存同步策略
     */
    CacheSyncPolicy getCacheSyncPolicy();

    /**
     * 设置缓存同步策略
     */
    CacheBuilder setCacheSyncPolicy(CacheSyncPolicy cacheSyncPolicy);

    /**
     * 获取真实的缓存Client实例
     * 注：主要用于二级缓存，一级缓存如果有需要可以使用
     */
    Object getActualCacheClient();

    /**
     * 设置真实的缓存Client实例
     * 注：主要是为了在使用二级缓存时留一个扩展点，可以直接设置应用中已经存在的缓存Client实例，如：RedissonClient、RedisTemplate 等
     */
    CacheBuilder setActualCacheClient(Object actualCacheClient);
}
