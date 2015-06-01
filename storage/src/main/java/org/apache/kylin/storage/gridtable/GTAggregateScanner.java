package org.apache.kylin.storage.gridtable;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;

import org.apache.kylin.common.util.ByteArray;
import org.apache.kylin.metadata.measure.MeasureAggregator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.BitSet;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.SortedMap;

@SuppressWarnings({ "rawtypes", "unchecked" })
public class GTAggregateScanner implements IGTScanner {

    @SuppressWarnings("unused")
    private static final Logger logger = LoggerFactory.getLogger(GTAggregateScanner.class);

    final GTInfo info;
    final BitSet dimensions; // dimensions to return, can be more than group by
    final BitSet groupBy;
    final BitSet metrics;
    final String[] metricsAggrFuncs;
    final IGTScanner inputScanner;
    
    long estimateSizeOfAggrCache;

    public GTAggregateScanner(IGTScanner inputScanner, GTScanRequest req) {
        if (req.hasAggregation() == false)
            throw new IllegalStateException();

        this.info = inputScanner.getInfo();
        this.dimensions = (BitSet) req.getColumns().clone();
        this.dimensions.andNot(req.getAggrMetrics());
        this.groupBy = req.getAggrGroupBy();
        this.metrics = req.getAggrMetrics();
        this.metricsAggrFuncs = req.getAggrMetricsFuncs();
        this.inputScanner = inputScanner;
    }

    @Override
    public GTInfo getInfo() {
        return info;
    }

    @Override
    public int getScannedRowCount() {
        return inputScanner.getScannedRowCount();
    }

    @Override
    public int getScannedRowBlockCount() {
        return inputScanner.getScannedRowBlockCount();
    }

    @Override
    public void close() throws IOException {
        inputScanner.close();
    }

    @Override
    public Iterator<GTRecord> iterator() {
        AggregationCache aggrCache = new AggregationCache();
        for (GTRecord r : inputScanner) {
            aggrCache.aggregate(r);
        }
        
        estimateSizeOfAggrCache = aggrCache.esitmateMemSize();
        
        return aggrCache.iterator();
    }
    
    /** for last call to iterator(), return the estimate memory size of its aggregation cache */
    public long getEstimateSizeOfAggrCache() {
        return estimateSizeOfAggrCache;
    }

    class AggregationCache {
        final SortedMap<byte[], MeasureAggregator[]> aggBufMap;
        final int keyLength;
        final boolean[] compareMask;

        public AggregationCache() {
            compareMask = createCompareMask();
            keyLength = compareMask.length;
            aggBufMap = Maps.newTreeMap(new Comparator<byte[]>() {
                @Override
                public int compare(byte[] o1, byte[] o2) {
                    int result = 0;
                    Preconditions.checkArgument(keyLength == o1.length && keyLength == o2.length);
                    for (int i = 0; i < keyLength; ++i) {
                        if (compareMask[i]) {
                            int a = (o1[i] & 0xff);
                            int b = (o2[i] & 0xff);
                            result = a - b;
                            if (result == 0) {
                                continue;
                            } else {
                                return result;
                            }
                        }
                    }
                    return result;
                }
            });
        }

        private boolean[] createCompareMask() {
            int keyLength = 0;
            for (int i = dimensions.nextSetBit(0); i >= 0; i = dimensions.nextSetBit(i + 1)) {
                int l = info.codeSystem.maxCodeLength(i);
                keyLength += l;
            }

            boolean[] mask = new boolean[keyLength];
            int p = 0;
            for (int i = dimensions.nextSetBit(0); i >= 0; i = dimensions.nextSetBit(i + 1)) {
                int l = info.codeSystem.maxCodeLength(i);
                boolean m = groupBy.get(i) ? true : false;
                for (int j = 0; j < l; j++) {
                    mask[p++] = m;
                }
            }
            return mask;
        }

        private byte[] createKey(GTRecord record) {
            byte[] result = new byte[keyLength];
            int offset = 0;
            for (int i = dimensions.nextSetBit(0); i >= 0; i = dimensions.nextSetBit(i + 1)) {
                final ByteArray byteArray = record.cols[i];
                final int columnLength = info.codeSystem.maxCodeLength(i);
                System.arraycopy(byteArray.array(), byteArray.offset(), result, offset, byteArray.length());
                offset += columnLength;
            }
            assert offset == result.length;
            return result;
        }

        void aggregate(GTRecord r) {
            final byte[] key = createKey(r);
            MeasureAggregator[] aggrs = aggBufMap.get(key);
            if (aggrs == null) {
                aggrs = new MeasureAggregator[metricsAggrFuncs.length];
                for (int i = 0, col = -1; i < aggrs.length; i++) {
                    col = metrics.nextSetBit(col + 1);
                    aggrs[i] = info.codeSystem.newMetricsAggregator(metricsAggrFuncs[i], col);
                }
                aggBufMap.put(key, aggrs);
            }
            for (int i = 0, col = -1; i < aggrs.length; i++) {
                col = metrics.nextSetBit(col + 1);
                Object metrics = info.codeSystem.decodeColumnValue(col, r.cols[col].asBuffer());
                aggrs[i].aggregate(metrics);
            }
        }
        
        public long esitmateMemSize() {
            if (aggBufMap.isEmpty())
                return 0;
            
            byte[] sampleKey = aggBufMap.firstKey();
            MeasureAggregator<?>[] sampleValue = aggBufMap.get(sampleKey);
            return estimateSizeOfAggrCache(sampleKey, sampleValue, aggBufMap.size());
        }

        public Iterator<GTRecord> iterator() {
            return new Iterator<GTRecord>() {

                final Iterator<Entry<byte[], MeasureAggregator[]>> it = aggBufMap.entrySet().iterator();

                final ByteBuffer metricsBuf = ByteBuffer.allocate(info.getMaxColumnLength(metrics));
                final GTRecord secondRecord = new GTRecord(info);

                @Override
                public boolean hasNext() {
                    return it.hasNext();
                }

                @Override
                public GTRecord next() {
                    Entry<byte[], MeasureAggregator[]> entry = it.next();
                    create(entry.getKey(), entry.getValue());
                    return secondRecord;
                }

                private void create(byte[] key, MeasureAggregator[] value) {
                    int offset = 0;
                    for (int i = dimensions.nextSetBit(0); i >= 0; i = dimensions.nextSetBit(i + 1)) {
                        final int columnLength = info.codeSystem.maxCodeLength(i);
                        secondRecord.set(i, new ByteArray(key, offset, columnLength));
                        offset += columnLength;
                    }
                    metricsBuf.clear();
                    for (int i = 0, col = -1; i < value.length; i++) {
                        col = metrics.nextSetBit(col + 1);
                        int pos = metricsBuf.position();
                        info.codeSystem.encodeColumnValue(col, value[i].getState(), metricsBuf);
                        secondRecord.cols[col].set(metricsBuf.array(), pos, metricsBuf.position() - pos);
                    }
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }

    public static long estimateSizeOfAggrCache(byte[] keySample, MeasureAggregator<?>[] aggrSample, int size) {
        // Aggregation cache is basically a tree map. The tree map entry overhead is
        // - 40 according to http://java-performance.info/memory-consumption-of-java-data-types-2/
        // - 41~52 according to AggregationCacheMemSizeTest
        return (estimateSizeOf(keySample) + estimateSizeOf(aggrSample) + 64) * size;
    }

    public static long estimateSizeOf(MeasureAggregator[] aggrs) {
        // size of array, AggregationCacheMemSizeTest reports 4 for [0], 12 for [1], 12 for [2], 20 for [3] etc..
        // Memory alignment to 8 bytes
        long est = (aggrs.length + 1) / 2 * 8 + 4 + (4 /* extra */);
        for (MeasureAggregator aggr : aggrs) {
            if (aggr != null)
                est += aggr.getMemBytesEstimate();
        }
        return est;
    }

    public static long estimateSizeOf(byte[] bytes) {
        // AggregationCacheMemSizeTest reports 20 for byte[10] and 20 again for byte[16]
        // Memory alignment to 8 bytes
        return (bytes.length + 7) / 8 * 8 + 4 + (4 /* extra */);
    }
}