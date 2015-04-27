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

package org.apache.kylin.streaming;

import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Created by qianzhou on 2/17/15.
 */
public abstract class StreamBuilder implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(StreamBuilder.class);

    private static final int BATCH_BUILD_INTERVAL_THRESHOLD = 2 * 60 * 1000;
    private final int sliceSize;
    private StreamParser streamParser = StringStreamParser.instance;

    private BlockingQueue<StreamMessage> streamMessageQueue;
    private long lastBuildTime = System.currentTimeMillis();

    public StreamBuilder(BlockingQueue<StreamMessage> streamMessageQueue, int sliceSize) {
        this.streamMessageQueue = streamMessageQueue;
        this.sliceSize = sliceSize;
    }

    protected abstract void build(List<StreamMessage> streamsToBuild) throws Exception;

    protected abstract void onStop();

    private void clearCounter() {
        lastBuildTime = System.currentTimeMillis();
    }

    @Override
    public void run() {
        try {
            List<StreamMessage> streamMessageToBuild = Lists.newArrayList();
            clearCounter();
            while (true) {
                StreamMessage streamMessage;
                try {
                    streamMessage = streamMessageQueue.poll(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    logger.warn("stream queue interrupted", e);
                    continue;
                }
                if (streamMessage == null) {

                    logger.info("The stream queue is drained, current available stream count: " + streamMessageToBuild.size());
                    if ((System.currentTimeMillis() - lastBuildTime) > BATCH_BUILD_INTERVAL_THRESHOLD) {
                        build(streamMessageToBuild);
                        clearCounter();
                        streamMessageToBuild.clear();
                    }
                    continue;
                } else {
                    if (streamMessage.getOffset() < 0) {
                        onStop();
                        logger.warn("streaming encountered EOF, stop building");
                        break;
                    }
                }
                streamMessageToBuild.add(streamMessage);
                if (streamMessageToBuild.size() >= this.sliceSize) {
                    build(streamMessageToBuild);
                    clearCounter();
                    streamMessageToBuild.clear();
                }
            }
        } catch (Exception e) {
            logger.error("build stream error, stop building", e);
            throw new RuntimeException("build stream error, stop building", e);
        }
    }

    public final StreamParser getStreamParser() {
        return streamParser;
    }

    public final void setStreamParser(StreamParser streamParser) {
        this.streamParser = streamParser;
    }
}
