/*
 *
 *
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *
 *  contributor license agreements. See the NOTICE file distributed with
 *
 *  this work for additional information regarding copyright ownership.
 *
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *
 *  (the "License"); you may not use this file except in compliance with
 *
 *  the License. You may obtain a copy of the License at
 *
 *
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *
 *
 *  Unless required by applicable law or agreed to in writing, software
 *
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *
 *  See the License for the specific language governing permissions and
 *
 *  limitations under the License.
 *
 * /
 */
package org.apache.kylin.job.hadoop.cubev2;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.util.StringUtils;
import org.apache.kylin.common.hll.HyperLogLogPlusCounter;
import org.apache.kylin.common.util.ByteArray;
import org.apache.kylin.common.util.Bytes;
import org.apache.kylin.common.util.BytesUtil;
import org.apache.kylin.common.util.Pair;
import org.apache.kylin.cube.CubeInstance;
import org.apache.kylin.cube.cuboid.Cuboid;
import org.apache.kylin.cube.cuboid.CuboidScheduler;
import org.apache.kylin.cube.model.CubeDesc;
import org.apache.kylin.cube.model.CubeJoinedFlatTableDesc;
import org.apache.kylin.cube.model.HBaseColumnDesc;
import org.apache.kylin.cube.model.HBaseColumnFamilyDesc;
import org.apache.kylin.dict.Dictionary;
import org.apache.kylin.metadata.measure.MeasureCodec;
import org.apache.kylin.metadata.model.FunctionDesc;
import org.apache.kylin.metadata.model.MeasureDesc;
import org.apache.kylin.metadata.model.TblColRef;
import org.apache.kylin.storage.cube.CubeGridTable;
import org.apache.kylin.storage.gridtable.*;
import org.apache.kylin.storage.gridtable.diskstore.GTDiskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

/**
 */
public class InMemCubeBuilder implements Runnable {

    private static Logger logger = LoggerFactory.getLogger(InMemCubeBuilder.class);
    private static final int DEFAULT_TIMEOUT = 25;

    private BlockingQueue<List<String>> queue;
    private CubeDesc desc = null;
    private long baseCuboidId;
    private CuboidScheduler cuboidScheduler = null;
    private Map<TblColRef, Dictionary<?>> dictionaryMap = null;
    private CubeJoinedFlatTableDesc intermediateTableDesc;
    private MeasureCodec measureCodec;
    private String[] metricsAggrFuncs = null;
    private Map<Integer, Integer> dependentMeasures = null; // key: index of Measure which depends on another measure; value: index of Measure which is depended on;
    public static final LongWritable ONE = new LongWritable(1l);
    private int[] hbaseMeasureRefIndex;
    private MeasureDesc[] measureDescs;
    private int measureCount;

    protected IGTRecordWriter gtRecordWriter;

    private static final int RESULT_OK = 0;
    private static final int RESULT_TIMEOUT = -1;
    private static final int RESULT_OUT_OF_MEMORY = -2;


    /**
     * @param queue
     * @param cube
     * @param dictionaryMap
     * @param gtRecordWriter
     */
    public InMemCubeBuilder(BlockingQueue<List<String>> queue, CubeInstance cube, Map<TblColRef, Dictionary<?>> dictionaryMap, IGTRecordWriter gtRecordWriter) {
        if (dictionaryMap == null || dictionaryMap.isEmpty()) {
            throw new IllegalArgumentException();
        }
        this.queue = queue;
        this.desc = cube.getDescriptor();
        this.cuboidScheduler = new CuboidScheduler(desc);
        this.dictionaryMap = dictionaryMap;
        this.gtRecordWriter = gtRecordWriter;
        this.baseCuboidId = Cuboid.getBaseCuboidId(desc);
        this.intermediateTableDesc = new CubeJoinedFlatTableDesc(desc, null);
        this.measureCodec = new MeasureCodec(desc.getMeasures());

        Map<String, Integer> measureIndexMap = Maps.newHashMap();
        List<String> metricsAggrFuncsList = Lists.newArrayList();
        measureCount = desc.getMeasures().size();

        List<MeasureDesc> measureDescsList = Lists.newArrayList();
        hbaseMeasureRefIndex = new int[measureCount];
        int measureRef = 0;
        for (HBaseColumnFamilyDesc familyDesc : desc.getHbaseMapping().getColumnFamily()) {
            for (HBaseColumnDesc hbaseColDesc : familyDesc.getColumns()) {
                for (MeasureDesc measure : hbaseColDesc.getMeasures()) {
                    for (int j = 0; j < measureCount; j++) {
                        if (desc.getMeasures().get(j).equals(measure)) {
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

        this.dependentMeasures = Maps.newHashMap();
        for (int i = 0; i < measureCount; i++) {
            String depMsrRef = measureDescsList.get(i).getDependentMeasureRef();
            if (depMsrRef != null) {
                int index = measureIndexMap.get(depMsrRef);
                dependentMeasures.put(i, index);
            }
        }

        this.measureDescs = desc.getMeasures().toArray(new MeasureDesc[measureCount]);
    }


    private GridTable newGridTableByCuboidID(long cuboidID, boolean memStore) {
        GTInfo info = CubeGridTable.newGTInfo(desc, cuboidID, dictionaryMap);
        GTComboStore store = new GTComboStore(info, memStore);
        GridTable gridTable = new GridTable(info, store);
        return gridTable;
    }

    private GridTable aggregateCuboid(GridTable parentCuboid, long parentCuboidId, long cuboidId) throws IOException {
        Pair<BitSet, BitSet> columnBitSets = getDimensionAndMetricColumnBitSet(parentCuboidId);
        BitSet parentDimensions = columnBitSets.getFirst();
        BitSet measureColumns = columnBitSets.getSecond();
        BitSet childDimensions = (BitSet) parentDimensions.clone();

        long mask = Long.highestOneBit(parentCuboidId);
        long childCuboidId = cuboidId;
        long parentCuboidIdActualLength = Long.SIZE - Long.numberOfLeadingZeros(parentCuboidId);
        int index = 0;
        for (int i = 0; i < parentCuboidIdActualLength; i++) {
            if ((mask & parentCuboidId) > 0) {
                if ((mask & childCuboidId) == 0) {
                    // this dim will be aggregated
                    childDimensions.set(index, false);
                }
                index++;
            }
            mask = mask >> 1;
        }

        return scanAndAggregateGridTable(parentCuboid, cuboidId, childDimensions, measureColumns);

    }

    private GridTable scanAndAggregateGridTable(GridTable gridTable, long cuboidId, BitSet aggregationColumns, BitSet measureColumns) throws IOException {
        GTScanRequest req = new GTScanRequest(gridTable.getInfo(), null, aggregationColumns, measureColumns, metricsAggrFuncs, null);
        IGTScanner scanner = gridTable.scan(req);
        GridTable newGridTable = newGridTableByCuboidID(cuboidId, true);
        GTBuilder builder = newGridTable.rebuild();

        BitSet allNeededColumns = new BitSet();
        allNeededColumns.or(aggregationColumns);
        allNeededColumns.or(measureColumns);

        GTRecord newRecord = new GTRecord(newGridTable.getInfo());
        int counter = 0;
        ByteArray byteArray = new ByteArray(8);
        ByteBuffer byteBuffer = ByteBuffer.allocate(8);
        try {
            BitSet dependentMetrics = new BitSet(allNeededColumns.cardinality());
            for (Integer i : dependentMeasures.keySet()) {
                dependentMetrics.set((allNeededColumns.cardinality() - measureCount + dependentMeasures.get(i)));
            }

            Object[] hllObjects = new Object[dependentMeasures.keySet().size()];

            for (GTRecord record : scanner) {
                counter++;
                for (int i = allNeededColumns.nextSetBit(0), index = 0; i >= 0; i = allNeededColumns.nextSetBit(i + 1), index++) {
                    newRecord.set(index, record.get(i));
                }

                if(dependentMeasures.size() > 0) {
                    // update measures which have 'dependent_measure_ref'
                    newRecord.getValues(dependentMetrics, hllObjects);

                    for (Integer i : dependentMeasures.keySet()) {
                        for (int index = 0, c = dependentMetrics.nextSetBit(0); c >= 0; index++, c = dependentMetrics.nextSetBit(c + 1)) {
                            if (c == allNeededColumns.cardinality() - measureCount + dependentMeasures.get(i)) {
                                assert hllObjects[index] instanceof HyperLogLogPlusCounter; // currently only HLL is allowed

                                byteBuffer.clear();
                                BytesUtil.writeVLong(((HyperLogLogPlusCounter) hllObjects[index]).getCountEstimate(), byteBuffer);
                                byteArray.set(byteBuffer.array(), 0, byteBuffer.position());
                                newRecord.set(allNeededColumns.cardinality() - measureCount + i, byteArray);
                            }
                        }

                    }
                }

                builder.write(newRecord);
            }
        } finally {
            builder.close();
        }
        logger.info("Cuboid " + cuboidId + " has rows: " + counter);

        return newGridTable;
    }

    private Pair<BitSet, BitSet> getDimensionAndMetricColumnBitSet(long cuboidId) {
        BitSet bitSet = BitSet.valueOf(new long[]{cuboidId});
        BitSet dimension = new BitSet();
        dimension.set(0, bitSet.cardinality());
        BitSet metrics = new BitSet();
        metrics.set(bitSet.cardinality(), bitSet.cardinality() + this.measureCount);
        return new Pair<BitSet, BitSet>(dimension, metrics);
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
            FunctionDesc function = desc.getMeasures().get(i).getFunction();
            if (function.isCount() || function.isHolisticCountDistinct()) {
                // note for holistic count distinct, this value will be ignored
                value = ONE;
            } else if (flatTableIdx == null) {
                value = measureCodec.getSerializer(i).valueOf(measureDesc.getFunction().getParameter().getValue());
            } else if (flatTableIdx.length == 1) {
                value = measureCodec.getSerializer(i).valueOf(Bytes.toBytes(row.get(flatTableIdx[0])));
            } else {

                byte[] result = null;
                for (int x = 0; x < flatTableIdx.length; x++) {
                    byte[] split = Bytes.toBytes(row.get(flatTableIdx[x]));
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


    @Override
    public void run() {
        try {
            logger.info("Create base cuboid " + baseCuboidId);
            final GridTable baseCuboidGT = newGridTableByCuboidID(baseCuboidId, false);

            GTBuilder baseGTBuilder = baseCuboidGT.rebuild();
            final GTRecord baseGTRecord = new GTRecord(baseCuboidGT.getInfo());

            IGTScanner queueScanner = new IGTScanner() {

                @Override
                public Iterator<GTRecord> iterator() {
                    return new Iterator<GTRecord>() {

                        List<String> currentObject = null;

                        @Override
                        public boolean hasNext() {
                            try {
                                currentObject = queue.take();
                            } catch (InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                            return currentObject != null && currentObject.size() > 0;
                        }

                        @Override
                        public GTRecord next() {
                            if (currentObject.size() == 0)
                                throw new IllegalStateException();

                            buildGTRecord(currentObject, baseGTRecord);
                            return baseGTRecord;
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
                    return baseCuboidGT.getInfo();
                }

                @Override
                public int getScannedRowCount() {
                    return 0;
                }

                @Override
                public int getScannedRowBlockCount() {
                    return 0;
                }
            };

            Pair<BitSet, BitSet> dimensionMetricsBitSet = getDimensionAndMetricColumnBitSet(baseCuboidId);
            GTScanRequest req = new GTScanRequest(baseCuboidGT.getInfo(), null, dimensionMetricsBitSet.getFirst(), dimensionMetricsBitSet.getSecond(), metricsAggrFuncs, null);
            IGTScanner aggregationScanner = new GTAggregateScanner(queueScanner, req);

            int counter = 0;
            for (GTRecord r : aggregationScanner) {
                baseGTBuilder.write(r);
                counter++;
            }
            baseGTBuilder.close();
            aggregationScanner.close();

            logger.info("Base cuboid has " + counter + " rows;");
            SimpleGridTableTree tree = new SimpleGridTableTree();
            tree.data = baseCuboidGT;
            tree.id = baseCuboidId;
            tree.parent = null;
            if (counter > 0) {
                List<Long> children = cuboidScheduler.getSpanningCuboid(baseCuboidId);
                Collections.sort(children);
                for (Long childId : children) {
                    createNDCuboidGT(tree, baseCuboidId, childId);
                }
            }
            outputGT(baseCuboidId, baseCuboidGT);
            dropStore(baseCuboidGT);

        } catch (IOException e) {
            logger.error("Fail to build cube", e);
            throw new RuntimeException(e);
        }

    }

    private void buildGTRecord(List<String> row, GTRecord record) {

        Object[] dimensions = buildKey(row);
        Object[] metricsValues = buildValue(row);
        Object[] recordValues = new Object[dimensions.length + metricsValues.length];
        System.arraycopy(dimensions, 0, recordValues, 0, dimensions.length);
        System.arraycopy(metricsValues, 0, recordValues, dimensions.length, metricsValues.length);
        record.setValues(recordValues);
    }

    private boolean gc(TreeNode<GridTable> parentNode) {
        final List<TreeNode<GridTable>> gridTables = parentNode.getAncestorList();
        logger.info("trying to select node to flush to disk, from:" + StringUtils.join(",", gridTables));
        for (TreeNode<GridTable> gridTable : gridTables) {
            final GTComboStore store = (GTComboStore) gridTable.data.getStore();
            if (store.memoryUsage() > 0) {
                logger.info("cuboid id:" + gridTable.id + " flush to disk");
                long t = System.currentTimeMillis();
                store.switchToDiskStore();
                logger.info("switch to disk store cost:" + (System.currentTimeMillis() - t) + "ms");
                waitForGc();
                return true;
            }
        }
        logger.warn("all ancestor nodes of " + parentNode.id + " has been flushed to disk");
        return false;

    }

    private Pair<Integer, GridTable> createChildCuboid(final GridTable parentCuboid, final long parentCuboidId, final long cuboidId, long timeoutInSeconds) {
        logger.info("Calculating cuboid " + cuboidId + " from parent " + parentCuboidId + " timeout: " + timeoutInSeconds + "seconds");
        final ExecutorService executorService = Executors.newSingleThreadExecutor();
        final Future<GridTable> task = executorService.submit(new Callable<GridTable>() {
            @Override
            public GridTable call() throws Exception {
                return aggregateCuboid(parentCuboid, parentCuboidId, cuboidId);
            }
        });
        try {
            return new Pair<>(RESULT_OK, task.get(timeoutInSeconds, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            throw new RuntimeException("no one will interrupt this thread, this should not happen", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof OutOfMemoryError) {
                logger.warn("Future.get() OutOfMemory, stop the thread");
                return new Pair<>(RESULT_OUT_OF_MEMORY, null);
            } else {
                logger.error("execution exception occurs", e);
                throw new RuntimeException(e);
            }
        } catch (TimeoutException e) {
            logger.warn("Future.get() timeout, stop the thread");
            return new Pair<>(RESULT_TIMEOUT, null);
        } finally {
            shutdownExecutorService(executorService);
        }
    }

    private void shutdownExecutorService(ExecutorService executorService) {
        final List<Runnable> runnables = executorService.shutdownNow();
        try {
            executorService.awaitTermination(DEFAULT_TIMEOUT, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException("no one will interrupt this thread, this should not happen", e);
        }
    }

    private void waitForGc() {
        System.gc();
        logger.info("wait 5 seconds for gc");
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            throw new RuntimeException("should not happen", e);
        }
    }

    private void createNDCuboidGT(SimpleGridTableTree parentNode, long parentCuboidId, long cuboidId) throws IOException {

        long startTime = System.currentTimeMillis();
        GridTable currentCuboid = null;
        int retryTimes = 3;
        int timeout = ((GTComboStore) parentNode.data.getStore()).memoryUsage() > 0 ? DEFAULT_TIMEOUT:DEFAULT_TIMEOUT*2;
        Pair<Integer, GridTable> result = createChildCuboid(parentNode.data, parentCuboidId, cuboidId, timeout);
        try {
            do {
                final int resultCode = result.getFirst();
                if (resultCode == 0) {
                    currentCuboid = result.getSecond();
                    break;
                } else {
                    logger.warn("create child cuboid:" + cuboidId + " from parent:" + parentCuboidId + " failed, result code:" + resultCode);
                    if (resultCode == RESULT_OUT_OF_MEMORY) {
                        if (gc(parentNode)) {
                        } else {
                            logger.warn("all parent node has been flushed into disk, memory is still insufficient, usually due to Runtime.freeMemory() is not accurate, just wait for gc");
                            waitForGc();
                        }
                        MemoryChecker.enabled = false;
                        result = createChildCuboid(parentNode.data, parentCuboidId, cuboidId, timeout);
                        continue;
                    } else if (resultCode == RESULT_TIMEOUT) {
                        logger.info("increase timeout threshold, and gc");
                        timeout += DEFAULT_TIMEOUT;
                        gc(parentNode);
                        result = createChildCuboid(parentNode.data, parentCuboidId, cuboidId, timeout);
                        continue;
                    } else {
                        throw new RuntimeException("invalid result code:" + resultCode);
                    }
                }
            } while (--retryTimes > 0);
        } finally {
            MemoryChecker.enabled = true;
        }

        if (currentCuboid == null) {
            logger.error("unable to create child cuboid:" + cuboidId + " from parent:" + parentCuboidId);
            throw new RuntimeException("unable to create child cuboid:" + cuboidId + " from parent:" + parentCuboidId);
        }

        SimpleGridTableTree node = new SimpleGridTableTree();
        node.parent = parentNode;
        node.data = currentCuboid;
        node.id = cuboidId;
        parentNode.children.add(node);

        logger.info("Cuboid " + cuboidId + " build takes " + (System.currentTimeMillis() - startTime) + "ms");

        List<Long> children = cuboidScheduler.getSpanningCuboid(cuboidId);
        if (!children.isEmpty()) {
            Collections.sort(children); // sort cuboids
            for (Long childId : children) {
                createNDCuboidGT(node, cuboidId, childId);
            }
        }


        //output the grid table
        outputGT(cuboidId, currentCuboid);
        dropStore(currentCuboid);
        parentNode.children.remove(node);
        if (parentNode.children.size() > 0) {
            logger.info("cuboid:" + cuboidId + " has finished, parent node:" + parentNode.id + " need to switch to mem store");
            ((GTComboStore) parentNode.data.getStore()).switchToMemStore();
        }
    }

    private void dropStore(GridTable gt) throws IOException {
        ((GTComboStore) gt.getStore()).drop();
    }


    private void outputGT(Long cuboidId, GridTable gridTable) throws IOException {
        long startTime = System.currentTimeMillis();
        GTScanRequest req = new GTScanRequest(gridTable.getInfo(), null, null, null);
        IGTScanner scanner = gridTable.scan(req);
        for (GTRecord record : scanner) {
            this.gtRecordWriter.write(cuboidId, record);
        }
        logger.info("Cuboid" + cuboidId + " output takes " + (System.currentTimeMillis() - startTime) + "ms");
    }

    private static class TreeNode<T> {
        T data;
        long id;
        TreeNode<T> parent;
        List<TreeNode<T>> children = Lists.newArrayList();

        List<TreeNode<T>> getAncestorList() {
            ArrayList<TreeNode<T>> result = Lists.newArrayList();
            TreeNode<T> parent = this;
            while (parent != null) {
                result.add(parent);
                parent = parent.parent;
            }
            return Lists.reverse(result);
        }

        @Override
        public String toString() {
            return id + "";
        }
    }

    private static class SimpleGridTableTree extends TreeNode<GridTable> {
    }


}
