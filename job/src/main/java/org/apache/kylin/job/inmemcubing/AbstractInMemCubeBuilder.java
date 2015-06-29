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
package org.apache.kylin.job.inmemcubing;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

import org.apache.kylin.cube.model.CubeDesc;
import org.apache.kylin.dict.Dictionary;
import org.apache.kylin.metadata.model.TblColRef;
import org.apache.kylin.storage.gridtable.GTRecord;
import org.apache.kylin.storage.gridtable.GTScanRequest;
import org.apache.kylin.storage.gridtable.GridTable;
import org.apache.kylin.storage.gridtable.IGTScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An interface alike abstract class. Hold common tunable parameters and nothing more.
 */
abstract public class AbstractInMemCubeBuilder {

    private static Logger logger = LoggerFactory.getLogger(AbstractInMemCubeBuilder.class);

    final protected CubeDesc cubeDesc;
    final protected Map<TblColRef, Dictionary<?>> dictionaryMap;
    
    protected int taskThreadCount = 4;
    protected int reserveMemoryMB = 100;

    public AbstractInMemCubeBuilder(CubeDesc cubeDesc, Map<TblColRef, Dictionary<?>> dictionaryMap) {
        if(cubeDesc == null)
            throw new NullPointerException();
        if (dictionaryMap == null)
            throw new IllegalArgumentException("dictionary cannot be null");
        
        this.cubeDesc = cubeDesc;
        this.dictionaryMap = dictionaryMap;
    }
    
    public void setConcurrentThreads(int n) {
        this.taskThreadCount = n;
    }

    public void setReserveMemoryMB(int mb) {
        this.reserveMemoryMB = mb;
    }

    public Runnable buildAsRunnable(final BlockingQueue<List<String>> input, final ICuboidWriter output) {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    build(input, output);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }
    
    abstract public void build(BlockingQueue<List<String>> input, ICuboidWriter output) throws IOException;

    protected void outputCuboid(long cuboidId, GridTable gridTable, ICuboidWriter output) throws IOException {
        long startTime = System.currentTimeMillis();
        GTScanRequest req = new GTScanRequest(gridTable.getInfo(), null, null, null);
        IGTScanner scanner = gridTable.scan(req);
        for (GTRecord record : scanner) {
            output.write(cuboidId, record);
        }
        scanner.close();
        logger.info("Cuboid " + cuboidId + " output takes " + (System.currentTimeMillis() - startTime) + "ms");
    }
    

}
