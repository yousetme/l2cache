package com.coy.l2cache.cache;

import com.coy.l2cache.CacheConfig;
import com.coy.l2cache.consts.CacheType;
import com.coy.l2cache.content.NullValue;
import com.coy.l2cache.exception.RedisTrylockFailException;
import com.coy.l2cache.util.RandomUtil;
import com.coy.l2cache.util.SpringCacheExceptionUtil;
import org.redisson.api.BatchResult;
import org.redisson.api.RBatch;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Redisson RBucket Cache
 * <p>
 * 由于基于Redisson的RMapCache的缓存淘汰机制在大量key过期时，存在一个bug，导致获取已过期但还未被删除的key的值时，返回为null，所以改造为使用RBucket来实现。
 * 数据结构从hash改造为String，一方面解决RMapCache的缓存淘汰问题，另一方面，解决热点key的问题。
 *
 * @author chenck
 * @date 2020/10/2 22:00
 */
public class RedissonRBucketCache extends AbstractAdaptingCache implements Level2Cache {

    private static final Logger logger = LoggerFactory.getLogger(RedissonRBucketCache.class);

    private static final String SPLIT = ":";

    /**
     * redis config
     */
    private final CacheConfig.Redis redis;

    /**
     * RBucket String结构
     */
    private RedissonClient redissonClient;

    private RMap<Object, Object> map;

    public RedissonRBucketCache(String cacheName, CacheConfig cacheConfig, RedissonClient redissonClient) {
        super(cacheName, cacheConfig);
        this.redis = cacheConfig.getRedis();
        this.redissonClient = redissonClient;
        if (redis.isLock()) {
            map = redissonClient.getMap(cacheName);
        }
    }

    @Override
    public long getExpireTime() {
        return redis.getExpireTime();
    }

    @Override
    public Object buildKey(Object key) {
        if (redis.isDuplicate()) {
            // 根据 随机数 构建缓存key，用于获取缓存
            int duplicateIndex = RandomUtil.getRandomInt(0, redis.getDuplicateSize());
            return this.buildKeyByDuplicate(key.toString(), duplicateIndex);
        } else {
            return this.buildKeyBase(key);
        }
    }

    /**
     * 根据 复制品下标 构建缓存key
     * 注：用于操作复制品缓存数据
     *
     * @param duplicateIndex 复制品下标
     */
    private Object buildKeyByDuplicate(Object key, int duplicateIndex) {
        return this.buildKeyBase(key.toString() + duplicateIndex);
    }

    /**
     * 构建基础缓存key
     * 注：用于操作基本的缓存key
     */
    private Object buildKeyBase(Object key) {
        if (key == null || "".equals(key)) {
            throw new IllegalArgumentException("key不能为空");
        }
        StringBuilder sb = new StringBuilder(this.getCacheName()).append(SPLIT);
        sb.append(key.toString());
        return sb.toString();
    }

    @Override
    public String getCacheType() {
        return CacheType.REDIS.name().toLowerCase();
    }

    @Override
    public RedissonClient getActualCache() {
        return this.redissonClient;
    }

    /**
     * 获取 RBucket 对象
     *
     * @param cacheKey 已经拼接好的缓存key
     */
    private RBucket<Object> getBucket(String cacheKey) {
        RBucket<Object> bucket = redissonClient.getBucket(cacheKey);
        return bucket;
    }

    @Override
    public Object get(Object key) {
        String cacheKey = (String) buildKey(key);
        Object value = getBucket(cacheKey).get();
        logger.debug("[RedissonRBucketCache] get cache, cacheName={}, key={}, value={}", this.getCacheName(), cacheKey, value);
        return fromStoreValue(value);
    }

    @Override
    public <T> T get(Object key, Class<T> type) {
        Object value = this.get(key);
        if (null == value) {
            return null;
        }
        if (value != null && type != null && !type.isInstance(value)) {
            throw new IllegalStateException("[RedissonRBucketCache] Cached value is not of required type [" + type.getName() + "]: " + value);
        }
        return (T) value;
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        String cacheKey = (String) buildKey(key);
        RBucket<Object> bucket = getBucket(cacheKey);
        Object value = bucket.get();
        if (value != null) {
            logger.info("[RedissonRBucketCache] get(key, callable) from redis, cacheName={}, key={}, value={}", this.getCacheName(), cacheKey, value);
            return (T) fromStoreValue(value);
        }
        if (null == valueLoader) {
            logger.info("[RedissonRBucketCache] get(key, callable) callable is null, return null, cacheName={}, key={}", this.getCacheName(), cacheKey);
            return null;
        }
        RLock lock = null;
        if (redis.isLock() && null != map) {
            // 增加分布式锁，集群环境下同一时刻只会有一个加载数据的线程，解决ABA的问题，保证一级缓存二级缓存数据的一致性
            lock = map.getLock(key);
            if (redis.isTryLock()) {
                if (!lock.tryLock()) {
                    // 高并发场景下，拦截一部分请求将其快速失败，保证性能
                    logger.warn("[RedissonRBucketCache] 重复请求, get(key, callable) tryLock fastfail, return null, cacheName={}, key={}", this.getCacheName(), cacheKey);
                    throw new RedisTrylockFailException("重复请求 tryLock fastfail, key=" + cacheKey);
                }
            } else {
                lock.lock();
            }
        }
        try {
            if (redis.isLock()) {
                value = bucket.get();
            }
            if (value == null) {
                logger.debug("[RedissonRBucketCache] rlock, load data from target method, cacheName={}, key={}, isLock={}", this.getCacheName(), cacheKey, redis.isLock());
                value = valueLoader.call();
                logger.debug("[RedissonRBucketCache] rlock, cacheName={}, key={}, value={}, isLock={}", this.getCacheName(), cacheKey, value, redis.isLock());
                this.put(key, value);
            }
        } catch (Exception ex) {
            // 将异常包装spring cache异常
            throw SpringCacheExceptionUtil.warpper(key, valueLoader, ex);
        } finally {
            if (null != lock) {
                lock.unlock();
            }
        }
        return (T) fromStoreValue(value);
    }

    @Override
    public void put(Object key, Object value) {
        String cacheKey = (String) buildKeyBase(key);
        RBucket<Object> bucket = getBucket(cacheKey);
        if (!isAllowNullValues() && value == null) {
            bucket.delete();
            return;
        }

        value = toStoreValue(value);
        // 过期时间处理
        long expireTime = this.expireTimeDeal(value);
        if (expireTime > 0) {
            bucket.set(value, expireTime, TimeUnit.MILLISECONDS);
            logger.info("[RedissonRBucketCache] put cache, cacheName={}, expireTime={} ms, key={}, value={}", this.getCacheName(), expireTime, cacheKey, value);
        } else {
            bucket.set(value);
            logger.info("[RedissonRBucketCache] put cache, cacheName={}, key={}, value={}", this.getCacheName(), cacheKey, value);
        }
        // key复制品处理
        if (redis.isDuplicate()) {
            this.duplicatePut(key, value, redis.getDuplicateSize());
        }
    }

    @Override
    public Object putIfAbsent(Object key, Object value) {
        if (!isAllowNullValues() && value == null) {
            // 不允许为null，且cacheValue为null，则直接获取旧的缓存项并返回
            return this.get(key);
        }
        String cacheKey = (String) buildKeyBase(key);
        RBucket<Object> bucket = getBucket(cacheKey);
        Object oldValue = bucket.get();
        // 过期时间处理
        long expireTime = this.expireTimeDeal(value);
        boolean rslt = false;
        if (expireTime > 0) {
            rslt = bucket.trySet(value, expireTime, TimeUnit.MILLISECONDS);
            logger.info("[RedissonRBucketCache] putIfAbsent cache, cacheName={}, expireTime={} ms, rslt={}, key={}, value={}, oldValue={}", this.getCacheName(), expireTime, rslt, cacheKey, value, oldValue);
        } else {
            rslt = bucket.trySet(value);
            logger.info("[RedissonRBucketCache] putIfAbsent cache, cacheName={}, rslt={}, key={}, value={}, oldValue={}", this.getCacheName(), rslt, cacheKey, value, oldValue);
        }
        // key复制品处理
        if (rslt && redis.isDuplicate()) {
            this.duplicateTrySet(key, value, redis.getDuplicateSize());
        }
        return fromStoreValue(oldValue);
    }

    @Override
    public void evict(Object key) {
        String cacheKey = (String) buildKeyBase(key);
        boolean result = getBucket(cacheKey).delete();
        logger.info("[RedissonRBucketCache] evict cache, cacheName={}, key={}, result={}", this.getCacheName(), cacheKey, result);
        // key复制品处理
        if (redis.isDuplicate()) {
            // TODO
            //this.duplicateEvict(key, value, redis.getDuplicateSize());
        }
    }

    @Override
    public void clear() {
        logger.warn("[RedissonRBucketCache] not support clear all cache, cacheName={}", this.getCacheName());
    }

    @Override
    public boolean isExists(Object key) {
        String cacheKey = (String) buildKeyBase(key);
        boolean rslt = getBucket(cacheKey).isExists();
        logger.debug("[RedissonRBucketCache] key is exists, cacheName={}, key={}, rslt={}", this.getCacheName(), cacheKey, rslt);
        return rslt;
    }

    @Override
    public List<Object> batchGet(List<Object> keyList) {
        if (null == keyList || keyList.size() == 0) {
            return new ArrayList<>();
        }
        RBatch batch = redissonClient.createBatch();
        List<String> keyListStr = new ArrayList<>();
        keyList.forEach(key -> {
            keyListStr.add((String) buildKey(key));
        });
        keyListStr.forEach(key -> {
            batch.getBucket(key).getAsync();
        });
        BatchResult result = batch.execute();
        List<Object> response = result.getResponses();
        logger.debug("[RedissonRBucketCache] batchGet cache, cacheName={}, keyList={}, valueList={}", this.getCacheName(), keyListStr, response);
        if (null == response) {
            return new ArrayList<>();
        }
        List<Object> list = new ArrayList<>();
        response.forEach(value -> {
            if (null != fromStoreValue(value)) {
                list.add(value);
            }
        });
        return list;
    }

    @Override
    public <T> List<T> batchGet(List<Object> keyList, Class<T> type) {
        List<Object> list = batchGet(keyList);
        if (null == list || list.size() == 0) {
            return (List<T>) list;
        }
        return (List<T>) list;
    }

    public <T> void batchPut(Map<Object, T> dataMap) {
        if (null == dataMap || dataMap.size() == 0) {
            return;
        }
        RBatch batch = redissonClient.createBatch();
        dataMap.entrySet().forEach(entry -> {
            String key = (String) buildKeyBase(entry.getKey());
            Object value = toStoreValue(entry.getValue());
            // 过期时间处理
            long expireTime = this.expireTimeDeal(value);
            if (expireTime > 0) {
                batch.getBucket(key).setAsync(value, expireTime, TimeUnit.MILLISECONDS);
                logger.info("[RedissonRBucketCache] batchPut cache, cacheName={}, expireTime={} ms, key={}, value={}", this.getCacheName(), expireTime, key, value);
            } else {
                batch.getBucket(key).setAsync(value);
                logger.info("[RedissonRBucketCache] batchPut cache, cacheName={}, key={}, value={}", this.getCacheName(), key, value);
            }
            // key复制品处理
            if (!redis.isDuplicate()) {
                this.duplicatePutBuild(key, value, batch, redis.getDuplicateSize());
            }
        });
        BatchResult result = batch.execute();
        logger.debug("[RedissonRBucketCache] batchPut cache, cacheName={}, size={}, syncedSlaves={}", this.getCacheName(), dataMap.size(), result.getSyncedSlaves());
    }

    /**
     * 过期时间处理
     * 如果是null值，则单独设置其过期时间
     */
    private long expireTimeDeal(Object value) {
        long expireTime = this.getExpireTime();
        if (value instanceof NullValue) {
            expireTime = TimeUnit.SECONDS.toMillis(this.getNullValueExpireTimeSeconds());
        }
        if (expireTime < 0) {
            expireTime = 0;
        }
        return expireTime;
    }

    /**
     * 副本Put
     * 主要解决单个redis分片上热点key的问题，相当于原来存一份数据，现在存多份相同的数据，将热key的压力分散到多个分片。
     * 以redis内存空间来降低单分片压力。
     */
    private void duplicatePut(Object key, Object value, int duplicateSize) {
        RBatch batch = redissonClient.createBatch();

        this.duplicatePutBuild(key, value, batch, duplicateSize);

        BatchResult result = batch.execute();
        logger.debug("[RedissonRBucketCache] duplicatePut cache, cacheName={}, size={}, result={}", this.getCacheName(), result.getResponses().size(), result.getResponses());
    }

    /**
     * 构建副本
     */
    private void duplicatePutBuild(Object key, Object value, RBatch batch, int duplicateSize) {
        if (duplicateSize <= 0) {
            logger.warn("[RedissonRBucketCache] duplicatePut duplicateSize less than 0, not put, cacheName={}, duplicateSize={}, key={}, value={}", this.getCacheName(), duplicateSize, key, value);
            return;
        }

        value = toStoreValue(value);
        // 过期时间处理
        long expireTime = this.expireTimeDeal(value);

        String tempKey = "";
        for (int i = 0; i < duplicateSize; i++) {
            tempKey = (String) buildKeyByDuplicate(key.toString(), i);
            if (expireTime > 0) {
                batch.getBucket(tempKey).setAsync(value, expireTime, TimeUnit.MILLISECONDS);
                logger.info("[RedissonRBucketCache] duplicatePut cache, cacheName={}, expireTime={} ms, key={}, value={}", this.getCacheName(), expireTime, tempKey, value);
            } else {
                batch.getBucket(tempKey).setAsync(value);
                logger.info("[RedissonRBucketCache] duplicatePut cache, cacheName={}, key={}, value={}", this.getCacheName(), tempKey, value);
            }
        }
    }

    /**
     * 副本TrySet
     */
    private void duplicateTrySet(Object key, Object value, int duplicateSize) {
        RBatch batch = redissonClient.createBatch();

        this.duplicateTrySetBuild(key, value, batch, duplicateSize);

        BatchResult result = batch.execute();
        logger.debug("[RedissonRBucketCache] duplicatePut cache, cacheName={}, size={}, result={}", this.getCacheName(), result.getResponses().size(), result.getResponses());
    }

    /**
     * 构建副本
     */
    private void duplicateTrySetBuild(Object key, Object value, RBatch batch, int duplicateSize) {
        if (duplicateSize <= 0) {
            logger.warn("[RedissonRBucketCache] duplicateTrySet duplicateSize less than 0, not put, cacheName={}, duplicateSize={}, key={}, value={}", this.getCacheName(), duplicateSize, key, value);
            return;
        }

        value = toStoreValue(value);
        // 过期时间处理
        long expireTime = this.expireTimeDeal(value);

        String tempKey = "";
        for (int i = 0; i < duplicateSize; i++) {
            tempKey = (String) buildKeyByDuplicate(key.toString(), i);
            if (expireTime > 0) {
                batch.getBucket(tempKey).trySetAsync(value, expireTime, TimeUnit.MILLISECONDS);
                logger.info("[RedissonRBucketCache] duplicateTrySet cache, cacheName={}, expireTime={} ms, key={}, value={}", this.getCacheName(), expireTime, tempKey, value);
            } else {
                batch.getBucket(tempKey).trySetAsync(value);
                logger.info("[RedissonRBucketCache] duplicateTrySet cache, cacheName={}, key={}, value={}", this.getCacheName(), tempKey, value);
            }
        }
    }

}
