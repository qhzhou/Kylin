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

package org.apache.kylin.job;

import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;

import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.util.ToolRunner;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.util.AbstractKylinTestCase;
import org.apache.kylin.common.util.ClassUtil;
import org.apache.kylin.common.util.DateFormat;
import org.apache.kylin.common.util.Pair;
import org.apache.kylin.engine.mr.JobBuilderSupport;
import org.apache.kylin.engine.streaming.StreamingBatch;
import org.apache.kylin.engine.streaming.StreamingMessage;
import org.apache.kylin.invertedindex.IIInstance;
import org.apache.kylin.invertedindex.IIManager;
import org.apache.kylin.invertedindex.IISegment;
import org.apache.kylin.invertedindex.index.Slice;
import org.apache.kylin.invertedindex.model.IIDesc;
import org.apache.kylin.invertedindex.model.IIJoinedFlatTableDesc;
import org.apache.kylin.invertedindex.model.IIKeyValueCodec;
import org.apache.kylin.invertedindex.model.IIRow;
import org.apache.kylin.invertedindex.streaming.SliceBuilder;
import org.apache.kylin.job.common.ShellExecutable;
import org.apache.kylin.job.constant.ExecutableConstants;
import org.apache.kylin.job.engine.JobEngineConfig;
import org.apache.kylin.job.hadoop.invertedindex.IICreateHTableJob;
import org.apache.kylin.metadata.model.TblColRef;
import org.apache.kylin.metadata.realization.RealizationStatusEnum;
import org.apache.kylin.source.hive.HiveTableReader;
import org.apache.kylin.storage.hbase.HBaseConnection;
import org.apache.kylin.storage.hbase.steps.HBaseMetadataTestCase;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Lists;

/**
 */
public class BuildIIWithStreamTest {

    private static final Logger logger = LoggerFactory.getLogger(BuildIIWithStreamTest.class);

    private static final String[] II_NAME = new String[] { "test_kylin_ii_left_join", "test_kylin_ii_inner_join" };
    private IIManager iiManager;
    private KylinConfig kylinConfig;

    @BeforeClass
    public static void beforeClass() throws Exception {
        logger.info("Adding to classpath: " + new File(HBaseMetadataTestCase.SANDBOX_TEST_DATA).getAbsolutePath());
        ClassUtil.addClasspath(new File(HBaseMetadataTestCase.SANDBOX_TEST_DATA).getAbsolutePath());
        System.setProperty("hdp.version", "2.2.4.2-2"); // mapred-site.xml ref this
    }

    @Before
    public void before() throws Exception {
        HBaseMetadataTestCase.staticCreateTestMetadata(AbstractKylinTestCase.SANDBOX_TEST_DATA);
        DeployUtil.overrideJobJarLocations();

        kylinConfig = KylinConfig.getInstanceFromEnv();
        iiManager = IIManager.getInstance(kylinConfig);
        iiManager = IIManager.getInstance(kylinConfig);
        for (String iiInstance : II_NAME) {

            IIInstance ii = iiManager.getII(iiInstance);
            if (ii.getStatus() != RealizationStatusEnum.DISABLED) {
                ii.setStatus(RealizationStatusEnum.DISABLED);
                iiManager.updateII(ii);
            }
        }
    }

    private String createIntermediateTable(IIDesc desc, KylinConfig kylinConfig) throws IOException {
        IIJoinedFlatTableDesc intermediateTableDesc = new IIJoinedFlatTableDesc(desc);
        JobEngineConfig jobEngineConfig = new JobEngineConfig(kylinConfig);
        final String uuid = UUID.randomUUID().toString();
        final String useDatabaseHql = "USE " + kylinConfig.getHiveDatabaseForIntermediateTable() + ";";
        final String dropTableHql = JoinedFlatTable.generateDropTableStatement(intermediateTableDesc);
        final String createTableHql = JoinedFlatTable.generateCreateTableStatement(intermediateTableDesc, JobBuilderSupport.getJobWorkingDir(jobEngineConfig, uuid));
        String insertDataHqls;
        try {
            insertDataHqls = JoinedFlatTable.generateInsertDataStatement(intermediateTableDesc, jobEngineConfig);
        } catch (IOException e1) {
            e1.printStackTrace();
            throw new RuntimeException("Failed to generate insert data SQL for intermediate table.");
        }

        ShellExecutable step = new ShellExecutable();
        StringBuffer buf = new StringBuffer();
        buf.append("hive -e \"");
        buf.append(useDatabaseHql + "\n");
        buf.append(dropTableHql + "\n");
        buf.append(createTableHql + "\n");
        buf.append(insertDataHqls + "\n");
        buf.append("\"");

        step.setCmd(buf.toString());
        logger.info(step.getCmd());
        step.setName(ExecutableConstants.STEP_NAME_CREATE_FLAT_HIVE_TABLE);
        kylinConfig.getCliCommandExecutor().execute(step.getCmd(), null);
        return intermediateTableDesc.getTableName();
    }

    private void clearSegment(String iiName) throws Exception {
        IIInstance ii = iiManager.getII(iiName);
        ii.getSegments().clear();
        iiManager.updateII(ii);
    }

    private IISegment createSegment(String iiName) throws Exception {
        clearSegment(iiName);
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd");
        f.setTimeZone(TimeZone.getTimeZone("GMT"));

        long date1 = 0;
        long date2 = f.parse("2015-01-01").getTime();
        return buildSegment(iiName, date1, date2);
    }

    private IISegment buildSegment(String iiName, long startDate, long endDate) throws Exception {
        IIInstance iiInstance = iiManager.getII(iiName);
        IISegment segment = iiManager.buildSegment(iiInstance, startDate, endDate);
        iiInstance.getSegments().add(segment);
        iiManager.updateII(iiInstance);
        return segment;
    }

    private void buildII(String iiName) throws Exception {
        final IIDesc desc = iiManager.getII(iiName).getDescriptor();
        final String tableName = createIntermediateTable(desc, kylinConfig);
        logger.info("intermediate table name:" + tableName);

        HiveTableReader reader = new HiveTableReader("default", tableName);
        final List<TblColRef> tblColRefs = desc.listAllColumns();
        for (TblColRef tblColRef : tblColRefs) {
            if (desc.isMetricsCol(tblColRef)) {
                logger.info("matrix:" + tblColRef.getName());
            } else {
                logger.info("measure:" + tblColRef.getName());
            }
        }
        final IISegment segment = createSegment(iiName);
        final HTableInterface htable = HBaseConnection.get(KylinConfig.getInstanceFromEnv().getStorageUrl()).getTable(segment.getStorageLocationIdentifier());
        String[] args = new String[] { "-iiname", iiName, "-htablename", segment.getStorageLocationIdentifier() };
        ToolRunner.run(new IICreateHTableJob(), args);

        final IIDesc iiDesc = segment.getIIDesc();
        final SliceBuilder sliceBuilder = new SliceBuilder(desc, (short) 0, iiDesc.isUseLocalDictionary());

        List<String[]> sorted = getSortedRows(reader, desc.getTimestampColumn());
        int count = sorted.size();
        ArrayList<StreamingMessage> messages = Lists.newArrayList();
        for (String[] row : sorted) {
            if (messages.size() < iiDesc.getSliceSize()) {
                messages.add(parse(row));
            } else {
                build(sliceBuilder, new StreamingBatch(messages, Pair.newPair(System.currentTimeMillis(), System.currentTimeMillis())), htable);
                messages = Lists.newArrayList();
                messages.add((parse(row)));
            }
        }
        if (!messages.isEmpty()) {
            build(sliceBuilder, new StreamingBatch(messages, Pair.newPair(System.currentTimeMillis(), System.currentTimeMillis())), htable);
        }

        reader.close();
        logger.info("total record count:" + count + " htable:" + segment.getStorageLocationIdentifier());
        logger.info("stream build finished, htable name:" + segment.getStorageLocationIdentifier());
    }

    @Test
    public void test() throws Exception {
        for (String iiName : II_NAME) {
            buildII(iiName);
            IIInstance ii = iiManager.getII(iiName);
            if (ii.getStatus() != RealizationStatusEnum.READY) {
                ii.setStatus(RealizationStatusEnum.READY);
                iiManager.updateII(ii);
            }
        }
    }
    
    private void build(SliceBuilder sliceBuilder, StreamingBatch batch, HTableInterface htable) {
        final Slice slice = sliceBuilder.buildSlice(batch);
        try {
            loadToHBase(htable, slice, new IIKeyValueCodec(slice.getInfo()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    
    private void loadToHBase(HTableInterface hTable, Slice slice, IIKeyValueCodec codec) throws IOException {
        List<Put> data = Lists.newArrayList();
        for (IIRow row : codec.encodeKeyValue(slice)) {
            final byte[] key = row.getKey().get();
            final byte[] value = row.getValue().get();
            Put put = new Put(key);
            put.add(IIDesc.HBASE_FAMILY_BYTES, IIDesc.HBASE_QUALIFIER_BYTES, value);
            final ImmutableBytesWritable dictionary = row.getDictionary();
            final byte[] dictBytes = dictionary.get();
            if (dictionary.getOffset() == 0 && dictionary.getLength() == dictBytes.length) {
                put.add(IIDesc.HBASE_FAMILY_BYTES, IIDesc.HBASE_DICTIONARY_BYTES, dictBytes);
            } else {
                throw new RuntimeException("dict offset should be 0, and dict length should be " + dictBytes.length + " but they are" + dictionary.getOffset() + " " + dictionary.getLength());
            }
            data.add(put);
        }
        hTable.put(data);
        //omit hTable.flushCommits(), because htable is auto flush
    }

    private StreamingMessage parse(String[] row) {
        return new StreamingMessage(Lists.newArrayList(row), System.currentTimeMillis(), System.currentTimeMillis(), Collections.<String, Object>emptyMap());
    }

    private List<String[]> getSortedRows(HiveTableReader reader, final int tsCol) throws IOException {
        List<String[]> unsorted = Lists.newArrayList();
        while (reader.next()) {
            unsorted.add(reader.getRow());
        }
        Collections.sort(unsorted, new Comparator<String[]>() {
            @Override
            public int compare(String[] o1, String[] o2) {
                long t1 = DateFormat.stringToMillis(o1[tsCol]);
                long t2 = DateFormat.stringToMillis(o2[tsCol]);
                return Long.compare(t1, t2);
            }
        });
        return unsorted;
    }

}
