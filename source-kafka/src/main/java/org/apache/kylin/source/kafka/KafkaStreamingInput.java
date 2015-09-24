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
package org.apache.kylin.source.kafka;

import java.lang.reflect.Constructor;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.annotation.Nullable;

import kafka.cluster.Broker;
import kafka.javaapi.FetchResponse;
import kafka.javaapi.PartitionMetadata;
import kafka.message.MessageAndOffset;

import org.apache.commons.lang3.StringUtils;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.util.Pair;
import org.apache.kylin.cube.CubeInstance;
import org.apache.kylin.cube.CubeManager;
import org.apache.kylin.cube.model.CubeJoinedFlatTableDesc;
import org.apache.kylin.engine.streaming.IStreamingInput;
import org.apache.kylin.engine.streaming.StreamingBatch;
import org.apache.kylin.engine.streaming.StreamingManager;
import org.apache.kylin.engine.streaming.StreamingMessage;
import org.apache.kylin.metadata.model.IntermediateColumnDesc;
import org.apache.kylin.metadata.model.TblColRef;
import org.apache.kylin.source.kafka.config.KafkaClusterConfig;
import org.apache.kylin.source.kafka.config.KafkaConfig;
import org.apache.kylin.source.kafka.util.KafkaRequester;
import org.apache.kylin.source.kafka.util.KafkaUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.collect.Lists;

/**
 */
public class KafkaStreamingInput implements IStreamingInput {

    private static final Logger logger = LoggerFactory.getLogger(KafkaStreamingInput.class);

    @Override
    public StreamingBatch getBatchWithTimeWindow(String streaming, int id, long startTime, long endTime) {
        try {
            logger.info(String.format("prepare to get streaming batch, name:%s, id:%d, startTime:%d, endTime:%d", streaming, id, startTime, endTime));
            final KylinConfig kylinConfig = KylinConfig.getInstanceFromEnv();
            final KafkaConfigManager kafkaConfigManager = KafkaConfigManager.getInstance(kylinConfig);
            final KafkaConfig kafkaConfig = kafkaConfigManager.getKafkaConfig(streaming);
            final StreamingParser streamingParser = getStreamingParser(kafkaConfig);
            final ExecutorService executorService = Executors.newCachedThreadPool();
            final List<Future<List<StreamingMessage>>> futures = Lists.newArrayList();
            for (final KafkaClusterConfig kafkaClusterConfig : kafkaConfig.getKafkaClusterConfigs()) {
                final int partitionCount = KafkaRequester.getKafkaTopicMeta(kafkaClusterConfig).getPartitionIds().size();
                for (int i = 0; i < partitionCount; ++i) {
                    final StreamingMessageProducer producer = new StreamingMessageProducer(kafkaClusterConfig, i, Pair.newPair(startTime, endTime), kafkaConfig.getMargin(), streamingParser);
                    final Future<List<StreamingMessage>> future = executorService.submit(producer);
                    futures.add(future);
                }
            }
            List<StreamingMessage> messages = Lists.newLinkedList();
            for (Future<List<StreamingMessage>> future : futures) {
                try {
                    messages.addAll(future.get());
                } catch (InterruptedException e) {
                    logger.warn("this thread should not be interrupted, just ignore", e);
                    continue;
                } catch (ExecutionException e) {
                    logger.error("error when get StreamingMessages", e.getCause());
                    continue;
                }
            }
            final Pair<Long, Long> timeRange = Pair.newPair(startTime, endTime);
            logger.info("finish to get streaming batch, total message count:" + messages.size());
            return new StreamingBatch(messages, timeRange);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("failed to create instance of StreamingParser", e);
        }
    }

    private static class StreamingMessageProducer implements Callable<List<StreamingMessage>> {

        private final KafkaClusterConfig kafkaClusterConfig;
        private final int partitionId;
        private final StreamingParser streamingParser;
        private final Pair<Long, Long> timeRange;
        private final long margin;

        private List<Broker> replicaBrokers;

        StreamingMessageProducer(KafkaClusterConfig kafkaClusterConfig, int partitionId, Pair<Long, Long> timeRange, long margin, StreamingParser streamingParser) {
            this.kafkaClusterConfig = kafkaClusterConfig;
            this.partitionId = partitionId;
            this.streamingParser = streamingParser;
            this.margin = margin;
            this.timeRange = timeRange;
            this.replicaBrokers = kafkaClusterConfig.getBrokers();
        }

        private Broker getLeadBroker() {
            final PartitionMetadata partitionMetadata = KafkaRequester.getPartitionMetadata(kafkaClusterConfig.getTopic(), partitionId, replicaBrokers, kafkaClusterConfig);
            if (partitionMetadata != null && partitionMetadata.errorCode() == 0) {
                replicaBrokers = partitionMetadata.replicas();
                return partitionMetadata.leader();
            } else {
                return null;
            }
        }

        @Override
        public List<StreamingMessage> call() throws Exception {
            List<StreamingMessage> result = Lists.newLinkedList();
            try {
                long startTimestamp = timeRange.getFirst() - margin;
                long offset = KafkaUtils.findClosestOffsetWithDataTimestamp(kafkaClusterConfig, partitionId, startTimestamp, streamingParser);
                int fetchRound = 0;
                int consumeMsgCount = 0;
                Broker leadBroker = null;
                String topic = kafkaClusterConfig.getTopic();
                while (true) {
                    boolean outOfMargin = false;
                    int consumeMsgCountAtBeginning = consumeMsgCount;
                    fetchRound++;

                    if (leadBroker == null) {
                        leadBroker = getLeadBroker();
                    }

                    if (leadBroker == null) {
                        logger.warn("cannot find lead broker, wait 5s");
                        Thread.sleep(5000);
                        continue;
                    }

                    logger.info("fetching topic {} partition id {} offset {} leader {}", new String[] { topic, String.valueOf(partitionId), String.valueOf(offset), leadBroker.toString() });

                    final FetchResponse fetchResponse = KafkaRequester.fetchResponse(topic, partitionId, offset, leadBroker, kafkaClusterConfig);
                    if (fetchResponse.errorCode(topic, partitionId) != 0) {
                        logger.warn("fetch response offset:" + offset + " errorCode:" + fetchResponse.errorCode(topic, partitionId));
                        Thread.sleep(30000);
                        continue;
                    }

                    for (MessageAndOffset messageAndOffset : fetchResponse.messageSet(topic, partitionId)) {
                        offset++;
                        consumeMsgCount++;
                        final StreamingMessage streamingMessage = streamingParser.parse(messageAndOffset);
                        if (streamingParser.filter(streamingMessage)) {
                            final long timestamp = streamingMessage.getTimestamp();
                            if (timestamp >= timeRange.getFirst() && timestamp < timeRange.getSecond()) {
                                result.add(streamingMessage);
                            } else if (timestamp < timeRange.getSecond() + margin) {
                                //do nothing
                            } else {
                                logger.info("thread:" + Thread.currentThread() + " message timestamp:" + timestamp + " is out of time range:" + timeRange + " margin:" + margin);
                                outOfMargin = true;
                                break;
                            }
                        }
                    }
                    logger.info("Number of messages consumed: " + consumeMsgCount + " offset is: " + offset + " total fetch round: " + fetchRound);
                    if (outOfMargin) {
                        break;
                    }
                    if (consumeMsgCount == consumeMsgCountAtBeginning) {//nothing this round
                        logger.info("no message consumed this round, wait 30s");
                        Thread.sleep(30000);
                    }
                }
            } catch (InterruptedException e) {
                logger.warn("this thread should not be interrupted, just stop fetching", e);
            }
            return result;
        }
    }

    private StreamingParser getStreamingParser(KafkaConfig kafkaConfig) throws ReflectiveOperationException {
        final String cubeName = StreamingManager.getInstance(KylinConfig.getInstanceFromEnv()).getStreamingConfig(kafkaConfig.getName()).getCubeName();
        final CubeInstance cubeInstance = CubeManager.getInstance(KylinConfig.getInstanceFromEnv()).getCube(cubeName);
        List<TblColRef> columns = Lists.transform(new CubeJoinedFlatTableDesc(cubeInstance.getDescriptor(), null).getColumnList(), new Function<IntermediateColumnDesc, TblColRef>() {
            @Nullable
            @Override
            public TblColRef apply(IntermediateColumnDesc input) {
                return input.getColRef();
            }
        });
        if (!StringUtils.isEmpty(kafkaConfig.getParserName())) {
            Class clazz = Class.forName(kafkaConfig.getParserName());
            Constructor constructor = clazz.getConstructor(List.class, String.class);
            return (StreamingParser) constructor.newInstance(columns, kafkaConfig.getParserProperties());
        } else {
            throw new IllegalStateException("invalid StreamingConfig:" + kafkaConfig.getName() + " missing property StreamingParser");
        }
    }

}
