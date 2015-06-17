package org.apache.kylin.storage.test;

import com.google.common.collect.Lists;
import com.google.common.collect.Range;
import com.google.common.collect.Ranges;
import org.apache.commons.lang.NotImplementedException;
import org.apache.kylin.common.util.DateFormat;
import org.apache.kylin.common.util.IdentityUtils;
import org.apache.kylin.metadata.model.FunctionDesc;
import org.apache.kylin.metadata.model.TblColRef;
import org.apache.kylin.metadata.realization.SQLDigest;
import org.apache.kylin.metadata.tuple.ITuple;
import org.apache.kylin.metadata.tuple.ITupleIterator;
import org.apache.kylin.metadata.tuple.SimpleTupleIterator;
import org.apache.kylin.storage.ICachableStorageEngine;
import org.apache.kylin.storage.StorageContext;
import org.apache.kylin.storage.cache.CacheFledgedDynamicStorageEngine;
import org.apache.kylin.storage.hbase.coprocessor.endpoint.TsConditionExtractor;
import org.apache.kylin.storage.tuple.Tuple;
import org.apache.kylin.storage.tuple.TupleInfo;
import org.junit.Assert;
import org.junit.Test;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 */
public class DynamicCacheTest {

    class TsOnlyTuple implements ITuple {
        private TblColRef partitionCol;
        private String tsStr;

        public TsOnlyTuple(TblColRef partitionCol, String tsStr) {
            this.partitionCol = partitionCol;
            this.tsStr = tsStr;
        }

        @Override
        public List<String> getAllFields() {
            throw new NotImplementedException();
        }

        @Override
        public List<TblColRef> getAllColumns() {
            throw new NotImplementedException();
        }

        @Override
        public Object[] getAllValues() {
            throw new NotImplementedException();
        }

        @Override
        public ITuple makeCopy() {
            return new TsOnlyTuple(this.partitionCol, this.tsStr);
        }

        @Override
        public Object getValue(TblColRef col) {
            if (col.equals(partitionCol)) {
                return Tuple.dateToEpicDays(this.tsStr);
            } else {
                throw new NotImplementedException();
            }
        }
    }

    @Test
    public void basicTest() {

        final StorageContext context = new StorageContext();
        final List<TblColRef> groups = StorageMockUtils.buildGroups();
        final TblColRef partitionCol = groups.get(0);
        final List<FunctionDesc> aggregations = StorageMockUtils.buildAggregations();
        final TupleInfo tupleInfo = StorageMockUtils.newTupleInfo(groups, aggregations);

        SQLDigest sqlDigest = new SQLDigest("default.test_kylin_fact", null, null, Lists.<TblColRef> newArrayList(), groups, Lists.newArrayList(partitionCol), Lists.<TblColRef>newArrayList(), aggregations);

        ITuple aTuple = new TsOnlyTuple(partitionCol, "2011-02-01");
        ITuple bTuple = new TsOnlyTuple(partitionCol, "2012-02-01");
        final List<ITuple> allTuples = Lists.newArrayList(aTuple, bTuple);

        //counts for verifying
        final AtomicInteger underlyingSEHitCount = new AtomicInteger(0);
        final List<Integer> returnedRowPerSearch = Lists.newArrayList();

        CacheFledgedDynamicStorageEngine dynamicCache = new CacheFledgedDynamicStorageEngine(new ICachableStorageEngine() {
            @Override
            public ITupleIterator search(StorageContext context, SQLDigest sqlDigest, TupleInfo returnTupleInfo) {
                Range<Long> tsRagneInQuery = TsConditionExtractor.extractTsCondition(partitionCol, sqlDigest.filter);
                List<ITuple> ret = Lists.newArrayList();
                for (ITuple tuple : allTuples) {
                    if (tsRagneInQuery.contains(Tuple.getTs(tuple, partitionCol))) {
                        ret.add(tuple);
                    }
                }

                underlyingSEHitCount.incrementAndGet();
                returnedRowPerSearch.add(ret.size());

                return new SimpleTupleIterator(ret.iterator());
            }

            @Override
            public boolean isDynamic() {
                return true;
            }

            @Override
            public Range<Long> getVolatilePeriod() {
                return Ranges.greaterThan(DateFormat.stringToMillis("2011-02-01"));
            }

            @Override
            public String getStorageUUID() {
                return "111ca32a-a33e-4b69-12aa-0bb8b1f8c191";
            }
        }, partitionCol);

        sqlDigest.filter = StorageMockUtils.buildTs2010Filter(groups.get(0));
        ITupleIterator firstIterator = dynamicCache.search(context, sqlDigest, tupleInfo);
        IdentityHashMap<ITuple, Void> firstResults = new IdentityHashMap<>();
        while (firstIterator.hasNext()) {
            firstResults.put(firstIterator.next(), null);
        }
        firstIterator.close();

        sqlDigest.filter = StorageMockUtils.buildTs2011Filter(groups.get(0));
        ITupleIterator secondIterator = dynamicCache.search(context, sqlDigest, tupleInfo);
        IdentityHashMap<ITuple, Void> secondResults = new IdentityHashMap<>();
        while (secondIterator.hasNext()) {
            secondResults.put(secondIterator.next(), null);
        }
        secondIterator.close();

        Assert.assertEquals(2, firstResults.size());
        IdentityUtils.collectionReferenceEquals(firstResults.keySet(), secondResults.keySet());
        Assert.assertEquals(2, underlyingSEHitCount.get());
        Assert.assertEquals(new Integer(2), returnedRowPerSearch.get(0));
        Assert.assertEquals(new Integer(1), returnedRowPerSearch.get(1));
    }
}
