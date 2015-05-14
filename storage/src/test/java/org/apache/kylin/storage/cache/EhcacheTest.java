package org.apache.kylin.storage.cache;

import com.google.common.collect.Lists;
import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Element;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.config.Configuration;
import net.sf.ehcache.config.PersistenceConfiguration;
import net.sf.ehcache.store.MemoryStoreEvictionPolicy;
import org.junit.Test;

import java.util.List;

/**
 */
public class EhcacheTest {
    @Test
    public void basicTest() throws InterruptedException {
        System.out.println("runtime used memory: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024 + "M");

        Configuration conf = new Configuration();
        conf.setMaxBytesLocalHeap("100M");
        CacheManager cacheManager = CacheManager.create(conf);

        //Create a Cache specifying its configuration.
        Cache testCache = //Create a Cache specifying its configuration.
        new Cache(new CacheConfiguration("test", 0).//
                memoryStoreEvictionPolicy(MemoryStoreEvictionPolicy.LRU).//
                eternal(false).//
                timeToIdleSeconds(86400).//
                diskExpiryThreadIntervalSeconds(0).//
                //maxBytesLocalHeap(1000, MemoryUnit.MEGABYTES).//
                persistence(new PersistenceConfiguration().strategy(PersistenceConfiguration.Strategy.NONE)));

        cacheManager.addCache(testCache);

        System.out.println("runtime used memory: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024 + "M");
        byte[] blob = new byte[(1024 * 40 * 1024)];//400M

        List<String> manyObjects = Lists.newArrayList();
        for (int i = 0; i < 10000; i++) {
            manyObjects.add(new String("" + i));
        }
        testCache.put(new Element("0", manyObjects));

        testCache.put(new Element("1", blob));
        System.out.println(testCache.get("1") == null);
        System.out.println(testCache.getSize());
        System.out.println(testCache.getStatistics().getLocalHeapSizeInBytes());
        System.out.println("runtime used memory: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024 + "M");
        testCache.put(new Element("2", blob));
        System.out.println(testCache.get("1") == null);
        System.out.println(testCache.getSize());
        System.out.println(testCache.getStatistics().getLocalHeapSizeInBytes());
        System.out.println("runtime used memory: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024 + "M");
        testCache.put(new Element("3", blob));
        System.out.println(testCache.get("1") == null);
        System.out.println(testCache.get("2") == null);
        System.out.println(testCache.get("3") == null);
        System.out.println(testCache.getSize());
        System.out.println(testCache.getStatistics().getLocalHeapSizeInBytes());
        System.out.println("runtime used memory: " + (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024 + "M");

        cacheManager.shutdown();
    }
}
