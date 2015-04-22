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

package org.apache.kylin.streaming.invertedindex;

import com.google.common.base.Stopwatch;
import com.google.common.collect.Lists;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.persistence.HBaseConnection;
import org.apache.kylin.invertedindex.index.Slice;
import org.apache.kylin.invertedindex.model.IIDesc;
import org.apache.kylin.invertedindex.model.IIKeyValueCodec;
import org.apache.kylin.invertedindex.model.IIRow;
import org.apache.kylin.streaming.StreamMessage;
import org.apache.kylin.streaming.StreamBuilder;
import org.apache.kylin.streaming.StreamingManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Created by qianzhou on 3/3/15.
 */
public class IIStreamBuilder extends StreamBuilder {

    private static Logger logger = LoggerFactory.getLogger(IIStreamBuilder.class);

    private final IIDesc desc;
    private final HTableInterface hTable;
    private final SliceBuilder sliceBuilder;
    private final int shardId;
    private final String streaming;
    private StreamingManager streamingManager;



    public IIStreamBuilder(BlockingQueue<StreamMessage> queue, String streaming, String hTableName, IIDesc iiDesc, int shard) {
        this(queue, streaming, hTableName, iiDesc, shard, true);
    }

    public IIStreamBuilder(BlockingQueue<StreamMessage> queue, String streaming, String hTableName, IIDesc iiDesc, int shard, boolean useLocalDict) {
        super(queue, iiDesc.getSliceSize());
        this.streaming = streaming;
        this.desc = iiDesc;
        this.shardId = shard;
        try {
            this.hTable = HBaseConnection.get(KylinConfig.getInstanceFromEnv().getStorageUrl()).getTable(hTableName);
        } catch (IOException e) {
            logger.error("cannot open htable name:" + hTableName, e);
            throw new RuntimeException("cannot open htable name:" + hTableName, e);
        }
        this.sliceBuilder = new SliceBuilder(desc, (short) shard, useLocalDict);
        this.streamingManager = StreamingManager.getInstance(KylinConfig.getInstanceFromEnv());
    }

    @Override
    protected void build(List<StreamMessage> streamsToBuild) throws IOException {
        if (streamsToBuild.size() > 0) {
            long offset = streamsToBuild.get(0).getOffset();
            if (offset < streamingManager.getOffset(streaming, shardId)) {
                logger.info("this batch has already been built, skip building");
                return;
            }
            logger.info("stream build start, size:" + streamsToBuild.size());
            Stopwatch stopwatch = Stopwatch.createStarted();
            final Slice slice = sliceBuilder.buildSlice(streamsToBuild, getStreamParser());
            logger.info("slice info, shard:" + slice.getShard() + " timestamp:" + slice.getTimestamp() + " record count:" + slice.getRecordCount());

            loadToHBase(hTable, slice, new IIKeyValueCodec(slice.getInfo()));
            submitOffset(offset);
            stopwatch.stop();
            logger.info("stream build finished, size:" + streamsToBuild.size() + " elapsed time:" + stopwatch.elapsed(TimeUnit.MILLISECONDS) + " " + TimeUnit.MILLISECONDS);
        } else {
            logger.info("nothing to build, skip building");
        }
    }

    private void loadToHBase(HTableInterface hTable, Slice slice, IIKeyValueCodec codec) throws IOException {
        try {
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
        } finally {
            hTable.close();
        }
    }

    private void submitOffset(long offset) {
        try {
            streamingManager.updateOffset(streaming, shardId, offset);
            logger.info("submit offset:" + offset);
        } catch (Exception e) {
            logger.warn("error submit offset: " + offset + " retrying", e);
            throw new RuntimeException(e);
        }
    }

}
