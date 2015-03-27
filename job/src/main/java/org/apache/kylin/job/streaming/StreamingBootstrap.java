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

package org.apache.kylin.job.streaming;

import com.google.common.base.Preconditions;
import com.google.common.collect.Maps;
import kafka.api.OffsetRequest;
import kafka.cluster.Broker;
import kafka.javaapi.PartitionMetadata;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.util.ToolRunner;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.invertedindex.IIInstance;
import org.apache.kylin.invertedindex.IIManager;
import org.apache.kylin.invertedindex.IISegment;
import org.apache.kylin.invertedindex.model.IIDesc;
import org.apache.kylin.job.hadoop.invertedindex.IICreateHTableJob;
import org.apache.kylin.streaming.*;
import org.apache.kylin.streaming.invertedindex.IIStreamBuilder;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Created by qianzhou on 3/26/15.
 */
public class StreamingBootstrap {

    private KylinConfig kylinConfig;
    private StreamManager streamManager;
    private IIManager iiManager;

    private Map<String, KafkaConsumer> kafkaConsumers = Maps.newConcurrentMap();

    public static StreamingBootstrap getInstance(KylinConfig kylinConfig) {
        final StreamingBootstrap bootstrap = new StreamingBootstrap(kylinConfig);
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                bootstrap.stop();
            }
        }));
        return bootstrap;
    }

    private StreamingBootstrap(KylinConfig kylinConfig) {
        this.kylinConfig = kylinConfig;
        this.streamManager = StreamManager.getInstance(kylinConfig);
        this.iiManager = IIManager.getInstance(kylinConfig);
    }

    private static Broker getLeadBroker(KafkaConfig kafkaConfig, int partitionId) {
        final PartitionMetadata partitionMetadata = KafkaRequester.getPartitionMetadata(kafkaConfig.getTopic(), partitionId, kafkaConfig.getBrokers(), kafkaConfig);
        if (partitionMetadata != null && partitionMetadata.errorCode() == 0) {
            return partitionMetadata.leader();
        } else {
            return null;
        }
    }

    public void stop() {
        for (KafkaConsumer consumer : kafkaConsumers.values()) {
            consumer.stop();
        }
    }

    public void start(String streaming, int partitionId) throws Exception {
        final KafkaConfig kafkaConfig = streamManager.getKafkaConfig(streaming);
        Preconditions.checkArgument(kafkaConfig != null, "cannot find kafka config:" + streaming);
        final IIInstance ii = iiManager.getII(kafkaConfig.getIiName());
        Preconditions.checkNotNull(ii);
        Preconditions.checkArgument(ii.getSegments().size() > 0);
        final IISegment iiSegment = ii.getSegments().get(0);

        final Broker leadBroker = getLeadBroker(kafkaConfig, partitionId);
        Preconditions.checkState(leadBroker != null, "cannot find lead broker");
        final long earliestOffset = KafkaRequester.getLastOffset(kafkaConfig.getTopic(), partitionId, OffsetRequest.EarliestTime(), leadBroker, kafkaConfig);
        long streamOffset = ii.getStreamOffsets().get(partitionId);
        if (streamOffset < earliestOffset) {
            streamOffset = earliestOffset;
        }
        String[] args = new String[]{"-iiname", kafkaConfig.getIiName(), "-htablename", iiSegment.getStorageLocationIdentifier()};
        ToolRunner.run(new IICreateHTableJob(), args);

        KafkaConsumer consumer = new KafkaConsumer(kafkaConfig.getTopic(), 0, streamOffset, kafkaConfig.getBrokers(), kafkaConfig) {
            @Override
            protected void consume(long offset, ByteBuffer payload) throws Exception {
                byte[] bytes = new byte[payload.limit()];
                payload.get(bytes);
                getStreamQueue().put(new Stream(offset, bytes));
            }
        };
        kafkaConsumers.put(getKey(streaming, partitionId), consumer);

        final IIStreamBuilder task = new IIStreamBuilder(consumer.getStreamQueue(), iiSegment.getStorageLocationIdentifier(), iiSegment.getIIInstance(), partitionId);
        task.setStreamParser(new JsonStreamParser(ii.getDescriptor().listAllColumns()));

        Executors.newSingleThreadExecutor().submit(consumer);
        Executors.newSingleThreadExecutor().submit(task).get();
    }

    private String getKey(String streaming, int partitionId) {
        return streaming + "_" + partitionId;
    }
}
