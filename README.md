# l2cache 的重要数据
- 目前已在生产环境投产，主要应用在商品中心、优惠券中心、用户中心等核心服务。
- 经历过2020年双十一，双十二，以及多次大促活动的流量洗礼。
- 支撑公司单月10亿GMV
- 支撑全链路压测32W+QPS


# L2cache  -- 基于内存和Redis的二级缓存框架

L2cache  是一个基于`内存`、 `Redis` 、 `Spring Cache` 实现的满足高并发场景下的分布式二级缓存框架。

**L2cache 的二级缓存结构：**

1、L1：一级缓存，内存缓存，`Caffeine` 和 `Guava Cache`。

2、L2：二级缓存，集中式缓存，支持`Redis`。

由于大量的缓存读取会导致 `L2` 的网络成为整个系统的瓶颈，因此 `L1` 的目标是降低对 `L2` 的读取次数。避免使用独立缓存系统所带来的网络IO开销问题。`L2` 可以避免应用重启后导致的 `L1`数据丢失的问题，同时无需担心`L1`会增加太多的内存消耗，因为你可以设置 `L1`中缓存数据的数量。

**说明：**


> `L2cache` 在满足高并发的同时也引入了一些新的问题，比如怎么保证分布式场景下各个节点中本地缓存的一致性问题，本框架采用`数据变更通知`+`定期刷新过期缓存` 的策略来尽可能的保证缓存的一致性。具体见下文中的 `分布式缓存同步` 和 `分布式缓存一致性保证` 两个章节。
>
> `L2cache` 满足CAP定理中的AP，也就是满足可用性和分区容错性，至于C(一致性)因为缓存的特性所以无法做到强一致性，只能尽可能的去做到一致性，保证最终的一致。

**关键点：**

支持根据配置`缓存类型`来灵活的组合使用不同的Cache。

1、支持只使用一级缓存`Caffeine` 和 `Guava Cache`。

2、支持只使用二级缓存`Redis`。

3、支持同时使用一二级缓存`Composite`。

 **必知：**

> 若使用缓存，则必然可能出现不一致的情况，也就是说无法保证强一致性。



# 如何使用L2cache

### 集成Spring项目

#### 1、启动L2cache

- **方式一：Enable 启动模式**

引入jar包`l2cache-core`

```xml
<dependency>
    <groupId>com.coy.l2cache</groupId>
    <artifactId>l2cache-core</artifactId>
    <version>1.0</version>
</dependency>
```

在SpringBoot启动类上标注 `@EnableL2Cache`  启动`l2cache`。

```java
/**
 * 通过 Spring Enable 注解模式来启用二级缓存组件
 */
@EnableL2Cache
@SpringBootApplication
public class TestApplication {
    public static void main(String[] args) {
        SpringApplication.run(TestApplication.class, args);
    }
}
```

- **方式二：starter 自动装载模式**

引入jar包 `l2cache-spring-boot-starter`
```xml
<dependency>
    <groupId>com.coy.l2cache</groupId>
    <artifactId>l2cache-spring-boot-starter</artifactId>
    <version>1.0</version>
</dependency>
```

#### 2、Spring配置

```yaml
spring:
  application:
    name: l2cache-example

# 二级缓存配置
l2cache:
  config:
    # 缓存实例Id，唯一标识应分布式场景下的一个缓存实例节点
    #instanceId: a1
    # 是否存储空值，默认true，防止缓存穿透
    allowNullValues: true
    # 空值过期时间，单位秒
    nullValueExpireTimeSeconds: 300
    # 缓存类型
    cacheType: composite
    # 组合缓存配置
    composite:
      # 一级缓存类型
      l1CacheType: caffeine
      # 二级缓存类型
      l2CacheType: redis
      # 是否全部启用一级缓存，默认false
      l1AllOpen: false
      # 是否手动启用一级缓存，默认false
      l1Manual: true
      # 手动配置走一级缓存的缓存key集合，针对单个key维度
      l1ManualKeySet:
        - userCache:user01
        - userCache:user02
      # 手动配置走一级缓存的缓存名字集合，针对cacheName维度
      l1ManualCacheNameSet:
        - compositeCache
        - goodsSpecCache
    # 一级缓存
    caffeine:
      # 是否构建异步Caffeine true 是 false 否
      asyncCache: false
      # 是否自动刷新过期缓存 true 是 false 否
      autoRefreshExpireCache: false
      # 缓存刷新调度线程池的大小
      refreshPoolSize: 2
      # 缓存刷新的频率(秒)
      refreshPeriod: 10
      # 高并发场景下建议使用refreshAfterWrite，在缓存过期后不会被回收，再次访问时会去刷新缓存，在新值没有加载完毕前，其他的线程访问始终返回旧值
      # Caffeine在缓存过期时默认只有一个线程去加载数据，配置了refreshAfterWrite后当大量请求过来时，可以确保其他用户快速获取响应。
      # 创建缓存的默认配置（完全与SpringCache中的Caffeine实现的配置一致）
      # 如果expireAfterWrite和expireAfterAccess同时存在，以expireAfterWrite为准。
      # 推荐用法：refreshAfterWrite 和 @Cacheable(sync=true)
      defaultSpec: initialCapacity=10,maximumSize=200,refreshAfterWrite=30m,recordStats
      # 设置指定缓存名的创建缓存配置(如：userCache为缓存名称)
      specs:
        userCache: initialCapacity=10,maximumSize=200,expireAfterWrite=30s
        userCacheSync: initialCapacity=10,maximumSize=200,refreshAfterWrite=30s,recordStats
        # 无过期时间配置
        queryUserSync: initialCapacity=10,maximumSize=2,recordStats
    # 二级缓存
    redis:
      # 加载数据时，是否加锁，默认false
      lock: false
      # 加锁时，true调用tryLock()，false调用lock()
      tryLock: true
      # 缓存过期时间(ms)
      # 注：作为默认的缓存过期时间，如果一级缓存设置了过期时间，则以一级缓存的过期时间为准。
      # 目的是为了支持cacheName维度的缓存过期时间设置
      #expireTime: 30000
      # Redisson 的yaml配置文件
      redissonYamlConfig: redisson.yaml
      # 缓存同步策略配置
    cacheSyncPolicy:
      # 策略类型 kafka / redis
      type: redis
      # 缓存更新时通知其他节点的topic名称
      topic: l2cache
```

注：
1、通过自定义`CacheLoader`结合到`Caffeine`或`Guava`的`LoadingCache`来实现数据加载。

2、建议所有缓存都设置过期时间，如果有些缓存维度可以是永久，那么也建议将过期时间设置长一些即可。



**关键点：**

支持根据配置来灵活的组合使用不同的Cache。

1、支持只使用一级缓存`Caffeine` 和 `Guava Cache`。

```yaml
l2cache:
  config:
    cacheType: caffeine
```

2、支持只使用二级缓存`Redis`。

```yaml
l2cache:
  config:
    cacheType: redis
```

3、支持同时使用一二级缓存。（推荐该方式，因为可动态配置缓存是走本地缓存还是走redis）

```yaml
l2cache:
  config:
    cacheType: composite
    composite:
      l1CacheType: caffeine
      l2CacheType: redis
```

4、支持配置指定缓存走本地缓存。

4.1）全部缓存 走本地缓存
```yaml
l2cache:
  config:
    cacheType: composite
    composite:
      l1CacheType: caffeine
      l2CacheType: redis
      # 是否全部启用一级缓存，默认false
      l1AllOpen: true
```

4.2）指定key 走本地缓存
```yaml
l2cache:
  config:
    cacheType: composite
    composite:
      l1CacheType: caffeine
      l2CacheType: redis
      # 是否全部启用一级缓存，默认false
      l1AllOpen: false
      # 是否手动启用一级缓存，默认false
      l1Manual: true
      # 手动配置走一级缓存的缓存key集合，针对单个key维度
      l1ManualKeySet:
        - userCache:user01
        - userCache:user02
```

4.3）指定缓存名字 走本地缓存
```yaml
l2cache:
  config:
    cacheType: composite
    composite:
      l1CacheType: caffeine
      l2CacheType: redis
      # 是否全部启用一级缓存，默认false
      l1AllOpen: false
      # 是否手动启用一级缓存，默认false
      l1Manual: true
      # 手动配置走一级缓存的缓存名字集合，针对cacheName维度
      l1ManualCacheNameSet:
        - compositeCache
        - goodsSpecCache
```

4.3）指定key + 指定缓存名字 走本地缓存
```yaml
l2cache:
  config:
    cacheType: composite
    composite:
      l1CacheType: caffeine
      l2CacheType: redis
      # 是否全部启用一级缓存，默认false
      l1AllOpen: false
      # 是否手动启用一级缓存，默认false
      l1Manual: true
      # 手动配置走一级缓存的缓存key集合，针对单个key维度
      l1ManualKeySet:
        - userCache:user01
        - userCache:user02
      # 手动配置走一级缓存的缓存名字集合，针对cacheName维度
      l1ManualCacheNameSet:
        - compositeCache
        - goodsSpecCache
```

#### 3、代码中的使用

结合Spring Cache的注解来使用。

```java
@Service
public class CaffeineCacheService {

    private final Logger logger = LoggerFactory.getLogger(CaffeineCacheService.class);

    /**
     * 用于模拟db
     */
    private static Map<String, User> userMap = new HashMap<>();

    {
        userMap.put("user01", new User("user01", "addr"));
        userMap.put("user02", new User("user03", "addr"));
        userMap.put("user03", new User("user03", "addr"));
    }

    /**
     * 获取或加载缓存项
     * <p>
     * 注：sync=false，CaffeineCache在定时刷新过期缓存时，是通过get(Object key)来获取缓存项，由于没有valueLoader（加载缓存项的具体逻辑），所以定时刷新缓存时，缓存项过期则会被淘汰。
     */
    @Cacheable(value = "userCache", key = "#userId")
    public User queryUser(String userId) {
        User user = userMap.get(userId);
        try {
            Thread.sleep(1000);// 模拟加载数据的耗时
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        logger.info("加载数据:{}", user);
        return user;
    }

    @Cacheable(value = "queryUserSync", key = "#userId", sync = true)
    public User queryUserSync(String userId) {
        User user = userMap.get(userId);
        logger.info("加载数据:{}", user);
        return user;
    }

    /**
     * 获取或加载缓存项
     * <p>
     * 注：因底层是基于caffeine来实现一级缓存，所以利用的caffeine本身的同步机制来实现
     * sync=true 则表示并发场景下同步加载缓存项，
     * sync=true，是通过get(Object key, Callable<T> valueLoader)来获取或加载缓存项，此时valueLoader（加载缓存项的具体逻辑）会被缓存起来，所以CaffeineCache在定时刷新过期缓存时，缓存项过期则会重新加载。
     * sync=false，是通过get(Object key)来获取缓存项，由于没有valueLoader（加载缓存项的具体逻辑），所以CaffeineCache在定时刷新过期缓存时，缓存项过期则会被淘汰。
     * <p>
     * 建议：设置@Cacheable的sync=true，可以利用Caffeine的特性，防止缓存击穿（方式同一个key和不同key）
     */
    @Cacheable(value = "queryUserSyncList", key = "#userId", sync = true)
    public List<User> queryUserSyncList(String userId) {
        User user = userMap.get(userId);
        List<User> list = new ArrayList();
        list.add(user);
        logger.info("加载数据:{}", list);
        return list;
    }

    /**
     * 设置缓存
     * 注：通过 @CachePut 标注的方法添加的缓存项，在CaffeineCache的定时刷新过期缓存任务执行时，缓存项过期则会被淘汰。
     * 如果先执行了 @Cacheable(sync = true) 标注的方法，再执行 @CachePut 标注的方法，那么在CaffeineCache的定时刷新过期缓存任务执行时，缓存项过期则会重新加载。
     */
    @CachePut(value = "userCacheSync", key = "#userId")
    public User putUser(String userId, User user) {
        return user;
    }

    /**
     * 淘汰缓存
     */
    @CacheEvict(value = "userCacheSync", key = "#userId")
    public String evictUserSync(String userId) {
        return userId;
    }
}
```



### 手动构建Cache

详细的构建方法参见如下单元测试类：

```
GuavaCacheTest
CaffeineCacheTest
RedisCacheTest
CompositeCacheTest
KafkaCacheSyncPolicyTest
```

下面列举`CaffeineCacheTest`中的一部分使用场景：

```java
CacheConfig cacheConfig = new CacheConfig();
CaffeineCache cache;
Callable<String> callable;

@before
public void before() {
    // 默认配置 CAFFEINE
    cacheConfig.setCacheType(CacheType.CAFFEINE.name())
        .setAllowNullValues(true)
        .getCaffeine()
.setDefaultSpec("initialCapacity=10,maximumSize=200,refreshAfterWrite=2s,recordStats")
        .setAutoRefreshExpireCache(true)
        .setRefreshPoolSize(3)
        .setRefreshPeriod(5L);

    cacheConfig.getCacheSyncPolicy()
        .setType(CacheSyncPolicyType.REDIS.name());

    // 构建缓存同步策略
    CacheSyncPolicy cacheSyncPolicy = new RedisCacheSyncPolicy()
        .setCacheConfig(cacheConfig)
        .setCacheMessageListener(new CacheMessageListener(cacheConfig.getInstanceId()))
        .setActualClient(Redisson.create());
    cacheSyncPolicy.connnect();

    // 构建cache
    cache = (CaffeineCache) new CaffeineCacheBuilder()
        .setCacheConfig(cacheConfig)
        .setExpiredListener(new DefaultCacheExpiredListener())
        .setCacheSyncPolicy(cacheSyncPolicy)
        .build("localCache");

    callable = new Callable<String>() {
        AtomicInteger count = new AtomicInteger(1);

        @Override
        public String call() throws Exception {
            String result = "loader_value" + count.getAndAdd(1);
            System.out.println("loader value from valueLoader, return " + result);
            return result;
        }
    };

    System.out.println("cacheName: " + cache.getCacheName());
    System.out.println("level: " + cache.getCacheName());
    System.out.println("actualCache: " + cache.getActualCache().getClass().getName());
    System.out.println();
}

@Test
public void caffeineCacheTest() throws InterruptedException {
    String key = "key1";
    String value = "value1";
    cache.put(key, value);// 设置缓存项

    Object value1 = cache.get(key);// 获取缓存项
    System.out.println(String.format("get key=%s, value=%s", key, value1));

    String value = cache.get(key, callable);// 获取或设置缓存项
    System.out.println(String.format("get key=%s, value=%s", key, value));
}
```



