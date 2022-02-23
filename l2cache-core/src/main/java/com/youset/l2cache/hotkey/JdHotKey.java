package com.youset.l2cache.hotkey;

import com.youset.l2cache.HotKey;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Function;

/**
 * @Author: zengjucai
 * @Date: 2021/6/10 13:45
 * 京东热key探测
 */
@Slf4j
public class JdHotKey implements HotKey {

    @Override
    public <K> boolean ifHotKey(K key, Function<K, Object> cacheKeyBuilder) {
//        if (ObjectUtil.isNull(key)) {
//            log.warn("jd hotkey param key is null");
//            return false;
//        }
//        Object apply = cacheKeyBuilder.apply(key);
//        return JdHotKeyStore.isHotKey(apply.toString());
        return false;
    }
}
