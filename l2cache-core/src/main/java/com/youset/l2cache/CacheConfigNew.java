package com.youset.l2cache;

import com.youset.l2cache.consts.CacheSyncPolicyType;
import com.youset.l2cache.consts.CacheType;
import com.youset.l2cache.util.RandomUtil;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * @author chenck
 * @date 2020/6/30 17:19
 */
@Getter
@Setter
@Accessors(chain = true)
public class CacheConfigNew {

    /**
     * 缓存实例id（默认为UUID）
     */
    private String instanceId = RandomUtil.getUUID();

    /**
     * 默认缓存配置
     */
    private Config defaultConfig;

    /**
     * 缓存配置集合
     * <key,value>=<cacheName, Config>=<缓存名称, 缓存配置>
     */
    private Map<String, Config> configMap = new HashMap<>();

    /**
     * 缓存配置
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Config {

        /**
         * 是否存储空值，设置为true时，可防止缓存穿透
         */
        private boolean allowNullValues = true;

        /**
         * NullValue的过期时间，单位秒，默认60秒
         * 用于淘汰NullValue的值
         * 注：当缓存项的过期时间小于该值时，则NullValue不会淘汰
         */
        private long nullValueExpireTimeSeconds = 60;

        /**
         * NullValue 的最大数量，防止出现内存溢出
         * 注：当超出该值时，会在下一次刷新缓存时，淘汰掉NullValue的元素
         */
        private long nullValueMaxSize = 3000;

        /**
         * NullValue 的清理频率(秒)
         */
        private long nullValueClearPeriodSeconds = 10L;

        /**
         * 是否动态根据cacheName创建Cache的实现，默认true
         */
        private boolean dynamic = true;

        /**
         * 缓存类型，默认 COMPOSITE 组合缓存
         *
         * @see CacheType
         */
        private String cacheType = CacheType.COMPOSITE.name();

        private final Composite composite = new Composite();
        private final Caffeine caffeine = new Caffeine();
        private final Guava guava = new Guava();
        private final Redis redis = new Redis();
        private final CacheSyncPolicy cacheSyncPolicy = new CacheSyncPolicy();
    }

    /**
     * 组合缓存配置
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Composite {
        /**
         * 一级缓存类型
         */
        private String l1CacheType = CacheType.CAFFEINE.name();
        /**
         * 二级缓存类型
         */
        private String l2CacheType = CacheType.REDIS.name();
        /**
         * 是否开启一级缓存，默认true开启
         * 注：便于动态控制一二级缓存
         */
        private boolean startupL1Cache = true;
    }

    /**
     * Caffeine specific cache properties.
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Caffeine {
        /**
         * 是否自动刷新过期缓存 true 表示是，false 表示否(默认)
         */
        private boolean autoRefreshExpireCache = false;

        /**
         * 缓存刷新调度线程池的大小，默认为CPU数
         */
        private Integer refreshPoolSize = Runtime.getRuntime().availableProcessors();

        /**
         * 缓存刷新的频率(秒)，默认30秒
         */
        private Long refreshPeriodSeconds = 30L;

        /**
         * The spec to use to create caches. See CaffeineSpec for more details on the spec format.
         */
        private String defaultSpec;

        /**
         * The spec to use to create caches. See CaffeineSpec for more details on the spec format.
         * <key,value>=<cacheName, spec>
         */
        @Deprecated
        private Map<String, String> specs = new HashMap<>();

    }

    /**
     * guava specific cache properties.
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Guava {
        /**
         * 是否自动刷新过期缓存 true 表示是，false 表示否(默认)
         */
        private boolean autoRefreshExpireCache = false;

        /**
         * 缓存刷新调度线程池的大小，默认为CPU数
         */
        private Integer refreshPoolSize = Runtime.getRuntime().availableProcessors();

        /**
         * 缓存刷新的频率(秒)，默认30秒
         */
        private Long refreshPeriodSeconds = 30L;

        /**
         * The spec to use to create caches. See CaffeineSpec for more details on the spec format.
         */
        private String defaultSpec;

        /**
         * The spec to use to create caches. See CaffeineSpec for more details on the spec format.
         * <key,value>=<cacheName, spec>
         */
        @Deprecated
        private Map<String, String> specs = new HashMap<>();
    }

    /**
     * Redis specific cache properties.
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    public static class Redis {

        /**
         * 加载数据时，是否加锁
         */
        private boolean lock = false;

        /**
         * 加载数据时是调用tryLock()，还是lock()
         * 注：
         * tryLock() 只有一个请求执行加载动作，其他并发请求，直接返回失败
         * lock() 只有一个请求执行加载动作，其他并发请求，会阻塞直到获得锁
         */
        private boolean tryLock = true;

        /**
         * 缓存过期时间(ms)
         * 注：作为默认的缓存过期时间，如果一级缓存设置了过期时间，则以一级缓存的过期时间为准。
         * 目的是为了支持cacheName维度的缓存过期时间设置
         */
        private long expireTimeMilliSeconds;

        /**
         * 是否启用副本，默认false
         * 主要解决单个redis分片上热点key的问题，相当于原来存一份数据，现在存多份相同的数据，将热key的压力分散到多个分片。
         * 以redis内存空间来降低单分片压力。
         */
        private boolean duplicate = false;

        /**
         * 针对所有key启用副本
         */
        private boolean duplicateALlKey = false;

        /**
         * 默认副本数量
         */
        private int defaultDuplicateSize = 10;

        /**
         * 副本缓存key集合
         * <key,副本数量>
         */
        private Map<String, Integer> duplicateKeyMap = new HashMap<>();

        /**
         * 副本缓存名字集合
         * <cacheName,副本数量>
         */
        private Map<String, Integer> duplicateCacheNameMap = new HashMap<>();

        /**
         * Redisson 的yaml配置文件
         */
        private String redissonYamlConfig;

        /**
         * Redisson Config
         */
        private org.redisson.config.Config redissonConfig;

        /**
         * 解析Redisson yaml文件
         */
        public org.redisson.config.Config getRedissonConfig() {
            if (StringUtils.isEmpty(this.redissonYamlConfig)) {
                return null;
            }
            if (null != redissonConfig) {
                return redissonConfig;
            }
            try {
                // 此方式可获取到springboot打包以后jar包内的资源文件
                InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(this.redissonYamlConfig);
                if (null == is) {
                    throw new IllegalStateException("not found redisson yaml config file:" + redissonYamlConfig);
                }
                redissonConfig = org.redisson.config.Config.fromYAML(is);
                return redissonConfig;
            } catch (IOException e) {
                throw new IllegalStateException("parse redisson yaml config error", e);
            }
        }

    }

    /**
     * 缓存同步策略配置
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    public static class CacheSyncPolicy {

        /**
         * 策略类型
         *
         * @see CacheSyncPolicyType
         */
        private String type;

        /**
         * 缓存更新时通知其他节点的topic名称
         */
        private String topic = "l2cache";

        /**
         * 是否支持异步发送消息
         */
        private boolean isAsync;

        /**
         * 具体的属性配置
         * 定义一个通用的属性字段，不同的MQ可配置各自的属性即可。
         * 如:kafka 的属性配置则完全与原生的配置保持一致
         */
        private Properties props = new Properties();
    }
}
