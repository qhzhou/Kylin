/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.apache.kylin.job.hadoop.cube;

import com.google.common.collect.Lists;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hive.hcatalog.data.HCatRecord;
import org.apache.hive.hcatalog.data.schema.HCatSchema;
import org.apache.hive.hcatalog.mapreduce.HCatInputFormat;
import org.apache.kylin.common.hll.HyperLogLogPlusCounter;
import org.apache.kylin.cube.cuboid.CuboidScheduler;
import org.apache.kylin.cube.kv.RowConstants;
import org.apache.kylin.cube.model.CubeJoinedFlatTableDesc;
import org.apache.kylin.dict.lookup.HiveTableReader;
import org.apache.kylin.job.constant.BatchConstants;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * @author yangli9
 */
public class FactDistinctHiveColumnsMapper<KEYIN> extends FactDistinctColumnsMapperBase<KEYIN, HCatRecord> {

    private HCatSchema schema = null;
    private CubeJoinedFlatTableDesc intermediateTableDesc;

    protected boolean collectStatistics = false;
    protected CuboidScheduler cuboidScheduler = null;
    protected int nRowKey;
    private Integer[][] allCuboidsBitSet = null;
    private HyperLogLogPlusCounter[] allCuboidsHLL = null;
    private Long[] cuboidIds;
    private BitSetIterator bitSetIterator = null;
    private List<String> rowArray;

    @Override
    protected void setup(Context context) throws IOException {
        super.setup(context);
        schema = HCatInputFormat.getTableSchema(context.getConfiguration());
        intermediateTableDesc = new CubeJoinedFlatTableDesc(cubeDesc, null);
        rowArray = new ArrayList<String>(schema.getFields().size());
        collectStatistics = Boolean.parseBoolean(context.getConfiguration().get(BatchConstants.CFG_STATISTICS_ENABLED));
        if (collectStatistics) {
            cuboidScheduler = new CuboidScheduler(cubeDesc);
            nRowKey = cubeDesc.getRowkey().getRowKeyColumns().length;

            List<Long> cuboidIdList = Lists.newArrayList();
            List<Integer[]> allCuboidsBitSetList = Lists.newArrayList();
            addCuboidBitSet(baseCuboidId, allCuboidsBitSetList, cuboidIdList);

            allCuboidsBitSet = allCuboidsBitSetList.toArray(new Integer[cuboidIdList.size()][]);
            cuboidIds = cuboidIdList.toArray(new Long[cuboidIdList.size()]);

            allCuboidsHLL = new HyperLogLogPlusCounter[cuboidIds.length];
            for (int i = 0; i < cuboidIds.length; i++) {
                allCuboidsHLL[i] = new HyperLogLogPlusCounter(16);
            }

            bitSetIterator = new BitSetIterator();
        }
    }

    private void addCuboidBitSet(long cuboidId, List<Integer[]> allCuboidsBitSet, List<Long> allCuboids) {
        allCuboids.add(cuboidId);
        BitSet bitSet = BitSet.valueOf(new long[]{cuboidId});
        Integer[] indice = new Integer[bitSet.cardinality()];

        long mask = Long.highestOneBit(baseCuboidId);
        int position = 0;
        for (int i = 0; i < nRowKey; i++) {
            if ((mask & cuboidId) > 0) {
                indice[position] = intermediateTableDesc.getRowKeyColumnIndexes()[i];
                position++;
            }
            mask = mask >> 1;
        }

        allCuboidsBitSet.add(indice);
        Collection<Long> children = cuboidScheduler.getSpanningCuboid(cuboidId);
        for (Long childId : children) {
            addCuboidBitSet(childId, allCuboidsBitSet, allCuboids);
        }
    }

    @Override
    public void map(KEYIN key, HCatRecord record, Context context) throws IOException, InterruptedException {
        rowArray.clear();
        HiveTableReader.getRowAsList(record, rowArray);
        try {
            for (int i : factDictCols) {
                outputKey.set((long) i);
                String fieldValue = rowArray.get(intermediateTableDesc.getRowKeyColumnIndexes()[i]);
                if (fieldValue == null)
                    continue;
                byte[] bytes = Bytes.toBytes(fieldValue);
                outputValue.set(bytes, 0, bytes.length);
                context.write(outputKey, outputValue);
            }
        } catch (Exception ex) {
            handleErrorRecord(record, ex);
        }

        if (collectStatistics) {
            putRowKeyToHLL(rowArray);
        }
    }

    private void putRowKeyToHLL(List<String> row) {
        bitSetIterator.array = row;
        for (int i = 0, n = allCuboidsBitSet.length; i < n; i++) {
            bitSetIterator.bitSet = allCuboidsBitSet[i];
            bitSetIterator.currentPosition = 0;

            allCuboidsHLL[i].add(StringUtils.join(bitSetIterator, ","));
        }
    }

    class BitSetIterator implements Iterator<String> {

        Integer[] bitSet = null;
        List<String> array = null;
        int currentPosition = 0;

        @Override
        public boolean hasNext() {
            return currentPosition < bitSet.length;
        }

        @Override
        public String next() {
            return array.get(bitSet[currentPosition++]);
        }

        @Override
        public void remove() {

        }
    }

    @Override
    protected void cleanup(Context context) throws IOException, InterruptedException {
        if (collectStatistics) {
            ByteBuffer hllBuf = ByteBuffer.allocate(RowConstants.ROWVALUE_BUFFER_SIZE);
            // output each cuboid's hll to reducer, key is 0 - cuboidId
            HyperLogLogPlusCounter hll;
            for (int i = 0; i < cuboidIds.length; i++) {
                hll = allCuboidsHLL[i];
                outputKey.set(0 - cuboidIds[i]);
                hllBuf.clear();
                hll.writeRegisters(hllBuf);
                outputValue.set(hllBuf.array(), 0, hllBuf.position());
                context.write(outputKey, outputValue);
            }

        }
    }

}
