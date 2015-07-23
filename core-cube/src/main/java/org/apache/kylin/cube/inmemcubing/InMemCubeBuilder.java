/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements. See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License. You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.kylin.cube.inmemcubing;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kylin.common.util.Bytes;
import org.apache.kylin.common.util.ImmutableBitSet;
import org.apache.kylin.common.util.MemoryBudgetController;
import org.apache.kylin.common.util.Pair;
import org.apache.kylin.cube.cuboid.Cuboid;
import org.apache.kylin.cube.cuboid.CuboidScheduler;
import org.apache.kylin.cube.gridtable.CubeGridTable;
import org.apache.kylin.cube.model.CubeDesc;
import org.apache.kylin.cube.model.CubeJoinedFlatTableDesc;
import org.apache.kylin.cube.model.HBaseColumnDesc;
import org.apache.kylin.cube.model.HBaseColumnFamilyDesc;
import org.apache.kylin.dict.Dictionary;
import org.apache.kylin.gridtable.GTAggregateScanner;
import org.apache.kylin.gridtable.GTBuilder;
import org.apache.kylin.gridtable.GTInfo;
import org.apache.kylin.gridtable.GTRecord;
import org.apache.kylin.gridtable.GTScanRequest;
import org.apache.kylin.gridtable.GridTable;
import org.apache.kylin.gridtable.IGTScanner;
import org.apache.kylin.metadata.measure.DoubleMutable;
import org.apache.kylin.metadata.measure.LongMutable;
import org.apache.kylin.metadata.measure.MeasureCodec;
import org.apache.kylin.metadata.model.FunctionDesc;
import org.apache.kylin.metadata.model.MeasureDesc;
import org.apache.kylin.metadata.model.TblColRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * Build a cube (many cuboids) in memory. Calculating multiple cuboids at the same time as long as memory permits.
 * Assumes base cuboid fits in memory or otherwise OOM exception will occur.
 */
public class InMemCubeBuilder extends AbstractInMemCubeBuilder {

    private static Logger logger = LoggerFactory.getLogger(InMemCubeBuilder.class);
    private static final LongMutable ONE = new LongMutable(1l);

    private final CuboidScheduler cuboidScheduler;
    private final long baseCuboidId;
    private final int totalCuboidCount;
    private final CubeJoinedFlatTableDesc intermediateTableDesc;
    private final MeasureCodec measureCodec;
    private final String[] metricsAggrFuncs;
    private final int[] hbaseMeasureRefIndex;
    private final MeasureDesc[] measureDescs;
    private final int measureCount;

    private MemoryBudgetController memBudget;
    private Thread[] taskThreads;
    private Throwable[] taskThreadExceptions;
    private LinkedBlockingQueue<CuboidTask> taskPending;
    private AtomicInteger taskCuboidCompleted = new AtomicInteger(0);

    private CuboidResult baseResult;
    private Object[] totalSumForSanityCheck;
    private ICuboidCollector resultCollector;

    public InMemCubeBuilder(CubeDesc cubeDesc, Map<TblColRef, Dictionary<?>> dictionaryMap) {
        super(cubeDesc, dictionaryMap);
        this.cuboidScheduler = new CuboidScheduler(cubeDesc);
        this.baseCuboidId = Cuboid.getBaseCuboidId(cubeDesc);
        this.totalCuboidCount = cuboidScheduler.getCuboidCount();
        this.intermediateTableDesc = new CubeJoinedFlatTableDesc(cubeDesc, null);
        this.measureCodec = new MeasureCodec(cubeDesc.getMeasures());

        Map<String, Integer> measureIndexMap = Maps.newHashMap();
        List<String> metricsAggrFuncsList = Lists.newArrayList();
        measureCount = cubeDesc.getMeasures().size();

        List<MeasureDesc> measureDescsList = Lists.newArrayList();
        hbaseMeasureRefIndex = new int[measureCount];
        int measureRef = 0;
        for (HBaseColumnFamilyDesc familyDesc : cubeDesc.getHbaseMapping().getColumnFamily()) {
            for (HBaseColumnDesc hbaseColDesc : familyDesc.getColumns()) {
                for (MeasureDesc measure : hbaseColDesc.getMeasures()) {
                    for (int j = 0; j < measureCount; j++) {
                        if (cubeDesc.getMeasures().get(j).equals(measure)) {
                            measureDescsList.add(measure);
                            hbaseMeasureRefIndex[measureRef] = j;
                            break;
                        }
                    }
                    measureRef++;
                }
            }
        }

        for (int i = 0; i < measureCount; i++) {
            MeasureDesc measureDesc = measureDescsList.get(i);
            metricsAggrFuncsList.add(measureDesc.getFunction().getExpression());
            measureIndexMap.put(measureDesc.getName(), i);
        }
        this.metricsAggrFuncs = metricsAggrFuncsList.toArray(new String[metricsAggrFuncsList.size()]);
        this.measureDescs = cubeDesc.getMeasures().toArray(new MeasureDesc[measureCount]);
    }

    private GridTable newGridTableByCuboidID(long cuboidID) throws IOException {
        GTInfo info = CubeGridTable.newGTInfo(cubeDesc, cuboidID, dictionaryMap);

        // Below several store implementation are very similar in performance. The ConcurrentDiskStore is the simplest.
        // MemDiskStore store = new MemDiskStore(info, memBudget == null ? MemoryBudgetController.ZERO_BUDGET : memBudget);
        // MemDiskStore store = new MemDiskStore(info, MemoryBudgetController.ZERO_BUDGET);
        ConcurrentDiskStore store = new ConcurrentDiskStore(info);

        GridTable gridTable = new GridTable(info, store);
        return gridTable;
    }

    private Pair<ImmutableBitSet, ImmutableBitSet> getDimensionAndMetricColumnBitSet(long cuboidId) {
        BitSet bitSet = BitSet.valueOf(new long[] { cuboidId });
        BitSet dimension = new BitSet();
        dimension.set(0, bitSet.cardinality());
        BitSet metrics = new BitSet();
        metrics.set(bitSet.cardinality(), bitSet.cardinality() + this.measureCount);
        return new Pair<ImmutableBitSet, ImmutableBitSet>(new ImmutableBitSet(dimension), new ImmutableBitSet(metrics));
    }

    @Override
    public void build(BlockingQueue<List<String>> input, ICuboidWriter output) throws IOException {
        ConcurrentNavigableMap<Long, CuboidResult> result = build(input);
        for (CuboidResult cuboidResult : result.values()) {
            outputCuboid(cuboidResult.cuboidId, cuboidResult.table, output);
            cuboidResult.table.close();
        }
    }

    ConcurrentNavigableMap<Long, CuboidResult> build(BlockingQueue<List<String>> input) throws IOException {
        final ConcurrentNavigableMap<Long, CuboidResult> result = new ConcurrentSkipListMap<Long, CuboidResult>();
        build(input, new ICuboidCollector() {
            @Override
            public void collect(CuboidResult cuboidResult) {
                result.put(cuboidResult.cuboidId, cuboidResult);
            }
        });
        return result;
    }

    interface ICuboidCollector {
        void collect(CuboidResult result);
    }

    static class CuboidResult {
        public long cuboidId;
        public GridTable table;
        public int nRows;
        public long timeSpent;
        public int aggrCacheMB;

        public CuboidResult(long cuboidId, GridTable table, int nRows, long timeSpent, int aggrCacheMB) {
            this.cuboidId = cuboidId;
            this.table = table;
            this.nRows = nRows;
            this.timeSpent = timeSpent;
            this.aggrCacheMB = aggrCacheMB;
        }
    }

    private void build(BlockingQueue<List<String>> input, ICuboidCollector collector) throws IOException {
        long startTime = System.currentTimeMillis();
        logger.info("In Mem Cube Build start, " + cubeDesc.getName());

        // multiple threads to compute cuboid in parallel
        taskPending = new LinkedBlockingQueue<>();
        taskCuboidCompleted.set(0);
        taskThreads = prepareTaskThreads();
        taskThreadExceptions = new Throwable[taskThreadCount];

        // build base cuboid
        resultCollector = collector;
        totalSumForSanityCheck = null;
        baseResult = createBaseCuboid(input);
        if (baseResult.nRows == 0)
            return;

        // plan memory budget
        makeMemoryBudget();

        // kick off N-D cuboid tasks and output
        addChildTasks(baseResult);
        start(taskThreads);

        // wait complete
        join(taskThreads);

        long endTime = System.currentTimeMillis();
        logger.info("In Mem Cube Build end, " + cubeDesc.getName() + ", takes " + (endTime - startTime) + " ms");

        throwExceptionIfAny();
    }

    public void abort() {
        interrupt(taskThreads);
    }

    private void start(Thread... threads) {
        for (Thread t : threads)
            t.start();
    }

    private void interrupt(Thread... threads) {
        for (Thread t : threads)
            t.interrupt();
    }

    private void join(Thread... threads) throws IOException {
        try {
            for (Thread t : threads)
                t.join();
        } catch (InterruptedException e) {
            throw new IOException("interrupted while waiting task and output complete", e);
        }
    }

    private void throwExceptionIfAny() throws IOException {
        ArrayList<Throwable> errors = new ArrayList<Throwable>();
        for (int i = 0; i < taskThreadCount; i++) {
            Throwable t = taskThreadExceptions[i];
            if (t != null)
                errors.add(t);
        }
        if (errors.isEmpty()) {
            return;
        } else if (errors.size() == 1) {
            Throwable t = errors.get(0);
            if (t instanceof IOException)
                throw (IOException) t;
            else
                throw new IOException(t);
        } else {
            for (Throwable t : errors)
                logger.error("Exception during in-mem cube build", t);
            throw new IOException(errors.size() + " exceptions during in-mem cube build, cause set to the first, check log for more", errors.get(0));
        }
    }

    private Thread[] prepareTaskThreads() {
        Thread[] result = new Thread[taskThreadCount];
        for (int i = 0; i < taskThreadCount; i++) {
            result[i] = new CuboidTaskThread(i);
        }
        return result;
    }

    public boolean isAllCuboidDone() {
        return taskCuboidCompleted.get() == totalCuboidCount;
    }

    private class CuboidTaskThread extends Thread {
        private int id;

        CuboidTaskThread(int id) {
            super("CuboidTask-" + id);
            this.id = id;
        }

        @Override
        public void run() {
            try {
                while (!isAllCuboidDone()) {
                    CuboidTask task = null;
                    while (task == null && taskHasNoException()) {
                        task = taskPending.poll(15, TimeUnit.SECONDS);
                    }
                    // if task error occurs
                    if (task == null)
                        break;

                    CuboidResult newCuboid = buildCuboid(task.parent, task.childCuboidId);
                    addChildTasks(newCuboid);

                    if (isAllCuboidDone()) {
                        for (Thread t : taskThreads) {
                            if (t != Thread.currentThread())
                                t.interrupt();
                        }
                    }
                }
            } catch (Throwable ex) {
                if (!isAllCuboidDone()) {
                    logger.error("task thread exception", ex);
                    taskThreadExceptions[id] = ex;
                }
            }
        }
    }

    private boolean taskHasNoException() {
        for (int i = 0; i < taskThreadExceptions.length; i++)
            if (taskThreadExceptions[i] != null)
                return false;
        return true;
    }

    private void addChildTasks(CuboidResult parent) {
        List<Long> children = cuboidScheduler.getSpanningCuboid(parent.cuboidId);
        for (Long child : children) {
            taskPending.add(new CuboidTask(parent, child));
        }
    }

    private int getSystemAvailMB() {
        Runtime.getRuntime().gc();
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            logger.error("", e);
        }
        return MemoryBudgetController.getSystemAvailMB();
    }

    private void makeMemoryBudget() {
        int systemAvailMB = getSystemAvailMB();
        logger.info("System avail " + systemAvailMB + " MB");
        int reserve = Math.max(reserveMemoryMB, baseResult.aggrCacheMB / 3);
        logger.info("Reserve " + reserve + " MB for system basics");

        int budget = systemAvailMB - reserve;
        if (budget < baseResult.aggrCacheMB) {
            // make sure we have base aggr cache as minimal
            budget = baseResult.aggrCacheMB;
            logger.warn("!!! System avail memory (" + systemAvailMB + " MB) is less than base aggr cache (" + baseResult.aggrCacheMB + " MB) + minimal reservation (" + reserve + " MB), consider increase JVM heap -Xmx");
        }

        logger.info("Memory Budget is " + budget + " MB");
        memBudget = new MemoryBudgetController(budget);
    }

    private CuboidResult createBaseCuboid(BlockingQueue<List<String>> input) throws IOException {
        GridTable baseCuboid = newGridTableByCuboidID(baseCuboidId);
        GTBuilder baseBuilder = baseCuboid.rebuild();
        IGTScanner baseInput = new InputConverter(baseCuboid.getInfo(), input);

        int mbBefore = getSystemAvailMB();
        int mbAfter = 0;

        Pair<ImmutableBitSet, ImmutableBitSet> dimensionMetricsBitSet = getDimensionAndMetricColumnBitSet(baseCuboidId);
        GTScanRequest req = new GTScanRequest(baseCuboid.getInfo(), null, dimensionMetricsBitSet.getFirst(), dimensionMetricsBitSet.getSecond(), metricsAggrFuncs, null);
        GTAggregateScanner aggregationScanner = new GTAggregateScanner(baseInput, req);

        long startTime = System.currentTimeMillis();
        logger.info("Calculating cuboid " + baseCuboidId);

        int count = 0;
        for (GTRecord r : aggregationScanner) {
            if (mbAfter == 0) {
                mbAfter = getSystemAvailMB();
            }
            baseBuilder.write(r);
            count++;
        }
        aggregationScanner.close();
        baseBuilder.close();

        long timeSpent = System.currentTimeMillis() - startTime;
        logger.info("Cuboid " + baseCuboidId + " has " + count + " rows, build takes " + timeSpent + "ms");

        int mbBaseAggrCacheOnHeap = mbAfter == 0 ? 0 : mbBefore - mbAfter;
        int mbEstimateBaseAggrCache = (int) (aggregationScanner.getEstimateSizeOfAggrCache() / MemoryBudgetController.ONE_MB);
        int mbBaseAggrCache = Math.max((int) (mbBaseAggrCacheOnHeap * 1.1), mbEstimateBaseAggrCache);
        mbBaseAggrCache = Math.max(mbBaseAggrCache, 10); // let it be 10 MB at least
        logger.info("Base aggr cache is " + mbBaseAggrCache + " MB (heap " + mbBaseAggrCacheOnHeap + " MB, estimate " + mbEstimateBaseAggrCache + " MB)");

        return updateCuboidResult(baseCuboidId, baseCuboid, count, timeSpent, mbBaseAggrCache);
    }

    private CuboidResult updateCuboidResult(long cuboidId, GridTable table, int nRows, long timeSpent, int aggrCacheMB) {
        if (aggrCacheMB <= 0) {
            aggrCacheMB = (int) Math.ceil(1.0 * nRows / baseResult.nRows * baseResult.aggrCacheMB);
        }

        CuboidResult result = new CuboidResult(cuboidId, table, nRows, timeSpent, aggrCacheMB);
        taskCuboidCompleted.incrementAndGet();

        resultCollector.collect(result);
        return result;
    }

    private CuboidResult buildCuboid(CuboidResult parent, long cuboidId) throws IOException {
        final String consumerName = "AggrCache@Cuboid " + cuboidId;
        MemoryBudgetController.MemoryConsumer consumer = new MemoryBudgetController.MemoryConsumer() {
            @Override
            public int freeUp(int mb) {
                return 0; // cannot free up on demand
            }

            @Override
            public String toString() {
                return consumerName;
            }
        };

        // reserve memory for aggregation cache, can't be larger than the parent
        memBudget.reserveInsist(consumer, parent.aggrCacheMB);
        try {
            return aggregateCuboid(parent, cuboidId);
        } finally {
            memBudget.reserve(consumer, 0);
        }
    }

    private CuboidResult aggregateCuboid(CuboidResult parent, long cuboidId) throws IOException {
        Pair<ImmutableBitSet, ImmutableBitSet> columnBitSets = getDimensionAndMetricColumnBitSet(parent.cuboidId);
        ImmutableBitSet parentDimensions = columnBitSets.getFirst();
        ImmutableBitSet measureColumns = columnBitSets.getSecond();
        ImmutableBitSet childDimensions = parentDimensions;

        long mask = Long.highestOneBit(parent.cuboidId);
        long childCuboidId = cuboidId;
        long parentCuboidIdActualLength = Long.SIZE - Long.numberOfLeadingZeros(parent.cuboidId);
        int index = 0;
        for (int i = 0; i < parentCuboidIdActualLength; i++) {
            if ((mask & parent.cuboidId) > 0) {
                if ((mask & childCuboidId) == 0) {
                    // this dim will be aggregated
                    childDimensions = childDimensions.set(index, false);
                }
                index++;
            }
            mask = mask >> 1;
        }

        return scanAndAggregateGridTable(parent.table, cuboidId, childDimensions, measureColumns);
    }

    private CuboidResult scanAndAggregateGridTable(GridTable gridTable, long cuboidId, ImmutableBitSet aggregationColumns, ImmutableBitSet measureColumns) throws IOException {
        long startTime = System.currentTimeMillis();
        logger.info("Calculating cuboid " + cuboidId);

        GTScanRequest req = new GTScanRequest(gridTable.getInfo(), null, aggregationColumns, measureColumns, metricsAggrFuncs, null);
        GTAggregateScanner scanner = (GTAggregateScanner) gridTable.scan(req);
        GridTable newGridTable = newGridTableByCuboidID(cuboidId);
        GTBuilder builder = newGridTable.rebuild();

        ImmutableBitSet allNeededColumns = aggregationColumns.or(measureColumns);

        GTRecord newRecord = new GTRecord(newGridTable.getInfo());
        int count = 0;
        try {
            for (GTRecord record : scanner) {
                count++;
                for (int i = 0; i < allNeededColumns.trueBitCount(); i++) {
                    int c = allNeededColumns.trueBitAt(i);
                    newRecord.set(i, record.get(c));
                }
                builder.write(newRecord);
            }

            // disable sanity check for performance
            sanityCheck(scanner.getTotalSumForSanityCheck());
        } finally {
            scanner.close();
            builder.close();
        }

        long timeSpent = System.currentTimeMillis() - startTime;
        logger.info("Cuboid " + cuboidId + " has " + count + " rows, build takes " + timeSpent + "ms");

        return updateCuboidResult(cuboidId, newGridTable, count, timeSpent, 0);
    }

    //@SuppressWarnings("unused")
    private void sanityCheck(Object[] totalSum) {
        // double sum introduces error and causes result not exactly equal
        for (int i = 0; i < totalSum.length; i++) {
            if (totalSum[i] instanceof DoubleMutable) {
                totalSum[i] = Math.round(((DoubleMutable) totalSum[i]).get());
            }
        }

        if (totalSumForSanityCheck == null) {
            totalSumForSanityCheck = totalSum;
            return;
        }
        if (Arrays.equals(totalSumForSanityCheck, totalSum) == false) {
            throw new IllegalStateException();
        }
    }

    // ===========================================================================

    private static class CuboidTask implements Comparable<CuboidTask> {
        final CuboidResult parent;
        final long childCuboidId;

        CuboidTask(CuboidResult parent, long childCuboidId) {
            this.parent = parent;
            this.childCuboidId = childCuboidId;
        }

        @Override
        public int compareTo(CuboidTask o) {
            long comp = this.childCuboidId - o.childCuboidId;
            return comp < 0 ? -1 : (comp > 0 ? 1 : 0);
        }
    }

    // ============================================================================

    private class InputConverter implements IGTScanner {
        GTInfo info;
        GTRecord record;
        BlockingQueue<List<String>> input;

        public InputConverter(GTInfo info, BlockingQueue<List<String>> input) {
            this.info = info;
            this.input = input;
            this.record = new GTRecord(info);
        }

        @Override
        public Iterator<GTRecord> iterator() {
            return new Iterator<GTRecord>() {

                List<String> currentObject = null;

                @Override
                public boolean hasNext() {
                    try {
                        currentObject = input.take();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    return currentObject != null && currentObject.size() > 0;
                }

                @Override
                public GTRecord next() {
                    if (currentObject.size() == 0)
                        throw new IllegalStateException();

                    buildGTRecord(currentObject, record);
                    return record;
                }

                @Override
                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public void close() throws IOException {
        }

        @Override
        public GTInfo getInfo() {
            return info;
        }

        @Override
        public int getScannedRowCount() {
            return 0;
        }

        @Override
        public int getScannedRowBlockCount() {
            return 0;
        }

        private void buildGTRecord(List<String> row, GTRecord record) {
            Object[] dimensions = buildKey(row);
            Object[] metricsValues = buildValue(row);
            Object[] recordValues = new Object[dimensions.length + metricsValues.length];
            System.arraycopy(dimensions, 0, recordValues, 0, dimensions.length);
            System.arraycopy(metricsValues, 0, recordValues, dimensions.length, metricsValues.length);
            record.setValues(recordValues);
        }

        private Object[] buildKey(List<String> row) {
            int keySize = intermediateTableDesc.getRowKeyColumnIndexes().length;
            Object[] key = new Object[keySize];

            for (int i = 0; i < keySize; i++) {
                key[i] = row.get(intermediateTableDesc.getRowKeyColumnIndexes()[i]);
            }

            return key;
        }

        private Object[] buildValue(List<String> row) {

            Object[] values = new Object[measureCount];
            MeasureDesc measureDesc = null;

            for (int position = 0; position < hbaseMeasureRefIndex.length; position++) {
                int i = hbaseMeasureRefIndex[position];
                measureDesc = measureDescs[i];

                Object value = null;
                int[] flatTableIdx = intermediateTableDesc.getMeasureColumnIndexes()[i];
                FunctionDesc function = cubeDesc.getMeasures().get(i).getFunction();
                if (function.isCount() || function.isHolisticCountDistinct()) {
                    // note for holistic count distinct, this value will be ignored
                    value = ONE;
                } else if (flatTableIdx == null) {
                    value = measureCodec.getSerializer(i).valueOf(measureDesc.getFunction().getParameter().getValue());
                } else if (flatTableIdx.length == 1) {
                    value = measureCodec.getSerializer(i).valueOf(toBytes(row.get(flatTableIdx[0])));
                } else {

                    byte[] result = null;
                    for (int x = 0; x < flatTableIdx.length; x++) {
                        byte[] split = toBytes(row.get(flatTableIdx[x]));
                        if (result == null) {
                            result = Arrays.copyOf(split, split.length);
                        } else {
                            byte[] newResult = new byte[result.length + split.length];
                            System.arraycopy(result, 0, newResult, 0, result.length);
                            System.arraycopy(split, 0, newResult, result.length, split.length);
                            result = newResult;
                        }
                    }
                    value = measureCodec.getSerializer(i).valueOf(result);
                }
                values[position] = value;
            }
            return values;
        }

        private byte[] toBytes(String v) {
            return v == null ? null : Bytes.toBytes(v);
        }

    }
}
