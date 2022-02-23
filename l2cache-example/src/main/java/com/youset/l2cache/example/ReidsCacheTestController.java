package com.youset.l2cache.example;

import com.youset.l2cache.spring.L2CacheCacheManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 *
 */
@Slf4j
@RestController
public class ReidsCacheTestController {

    @Autowired
    L2CacheCacheManager cacheManager;

    @RequestMapping(value = "/redis/put")
    public void put(String cacheName, String key, String value) {
        Cache cache = cacheManager.getCache(cacheName);
        cache.put(key, value);
    }

    @RequestMapping(value = "/redis/get")
    public void get(String cacheName, String key) {
        Cache cache = cacheManager.getCache(cacheName);
        cache.get(key);
    }

}
