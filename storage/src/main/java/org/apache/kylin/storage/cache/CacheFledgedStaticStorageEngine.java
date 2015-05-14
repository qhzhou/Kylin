package org.apache.kylin.storage.cache;

import com.google.common.collect.Range;
import com.google.common.collect.Ranges;
import net.sf.ehcache.Cache;
import net.sf.ehcache.Element;
import org.apache.kylin.metadata.realization.SQLDigest;
import org.apache.kylin.metadata.realization.StreamSQLDigest;
import org.apache.kylin.metadata.tuple.*;
import org.apache.kylin.storage.IStorageEngine;
import org.apache.kylin.storage.StorageContext;
import org.apache.kylin.storage.tuple.TupleInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Created by Hongbin Ma(Binmahone) on 5/11/15.
 */
public class CacheFledgedStaticStorageEngine extends AbstractCacheFledgedStorageEngine implements IStorageEngine, TeeTupleItrListener {
    private static final Logger logger = LoggerFactory.getLogger(CacheFledgedStaticStorageEngine.class);

    public CacheFledgedStaticStorageEngine(IStorageEngine underlyingStorage) {
        super(underlyingStorage);
    }

    @Override
    public ITupleIterator search(final StorageContext context, final SQLDigest sqlDigest, final TupleInfo returnTupleInfo) {

        streamSQLDigest = new StreamSQLDigest(sqlDigest, null);
        StreamSQLResult cachedResult = null;
        Cache cache = cacheManager.getCache(this.underlyingStorage.getClass().getName());
        Element element = cache.get(streamSQLDigest);
        if (element != null) {
            this.queryCacheExists = true;
            cachedResult = (StreamSQLResult) element.getObjectValue();
        }

        ITupleIterator ret = null;
        if (cachedResult != null) {
            ret = new SimpleTupleIterator(cachedResult.reuse(Ranges.<Long> all()));
        } else {
            logger.info("no cache entry for this query");
        }

        if (ret == null) {
            logger.info("decision: not using cache");
            ret = underlyingStorage.search(context, sqlDigest, returnTupleInfo);
        } else {
            logger.info("decision: use cache");
        }

        if (!queryCacheExists) {
            //use another nested ITupleIterator to deal with cache
            final TeeTupleIterator tee = new TeeTupleIterator(ret);
            tee.addCloseListener(this);
            return tee;
        } else {
            return ret;
        }
    }

    @Override
    public Range<Long> getVolatilePeriod() {
        return underlyingStorage.getVolatilePeriod();
    }

    @Override
    public boolean isDynamic() {
        return false;
    }

    @Override
    public void notify(List<ITuple> duplicated) {
        StreamSQLResult newCacheEntry = new StreamSQLResult(duplicated, Ranges.<Long> all(), null);
        cacheManager.getCache(this.underlyingStorage.getClass().getName()).put(new Element(streamSQLDigest, newCacheEntry));
        logger.info("cache after the query: " + newCacheEntry);
    }
}
