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

import org.apache.commons.lang3.StringUtils;
import org.apache.kylin.metadata.model.TblColRef;
import org.apache.kylin.streaming.JsonStreamParser;
import org.apache.kylin.streaming.StreamMessage;
import org.apache.kylin.streaming.StreamBuilder;

import java.util.List;
import java.util.concurrent.BlockingQueue;

/**
 * Created by qianzhou on 3/25/15.
 */
public class PrintOutStreamBuilder extends StreamBuilder {

    private final List<TblColRef> allColumns;

    public PrintOutStreamBuilder(BlockingQueue<StreamMessage> streamMessageQueue, int sliceSize, List<TblColRef> allColumns) {
        super(streamMessageQueue, sliceSize);
        setStreamParser(new JsonStreamParser(allColumns));
        this.allColumns = allColumns;
    }

    @Override
    protected void build(List<StreamMessage> streamsToBuild) throws Exception {
        for (StreamMessage streamMessage : streamsToBuild) {
            final List<String> row = getStreamParser().parse(streamMessage);
            System.out.println("offset:" + streamMessage.getOffset() + " " + StringUtils.join(row, ","));
        }
    }

    @Override
    protected void onStop() {

    }
}
