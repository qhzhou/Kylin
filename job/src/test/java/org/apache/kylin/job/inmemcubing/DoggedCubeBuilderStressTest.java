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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.util.LocalFileMetadataTestCase;
import org.apache.kylin.cube.CubeInstance;
import org.apache.kylin.cube.CubeManager;
import org.apache.kylin.dict.Dictionary;
import org.apache.kylin.metadata.model.TblColRef;
import org.apache.kylin.storage.gridtable.GTRecord;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 */
public class DoggedCubeBuilderStressTest extends LocalFileMetadataTestCase {

    @SuppressWarnings("unused")
    private static final Logger logger = LoggerFactory.getLogger(DoggedCubeBuilderStressTest.class);

    // CI sandbox memory is no more than 512MB, this many input should hit memory threshold
    private static final int INPUT_ROWS = 200000;
    private static final int THREADS = 4;

    private static CubeInstance cube;
    private static String flatTable;
    private static Map<TblColRef, Dictionary<?>> dictionaryMap;

    @BeforeClass
    public static void before() throws IOException {
        staticCreateTestMetadata();

        KylinConfig kylinConfig = KylinConfig.getInstanceFromEnv();
        CubeManager cubeManager = CubeManager.getInstance(kylinConfig);

        cube = cubeManager.getCube("test_kylin_cube_without_slr_left_join_empty");
        flatTable = "../examples/test_case_data/localmeta/data/flatten_data_for_without_slr_left_join.csv";
        dictionaryMap = InMemCubeBuilderTest.getDictionaryMap(cube, flatTable);
    }

    @AfterClass
    public static void after() throws Exception {
        staticCleanupTestMetadata();
    }

    @Test
    public void test() throws Exception {

        ArrayBlockingQueue<List<String>> queue = new ArrayBlockingQueue<List<String>>(1000);
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        long randSeed = System.currentTimeMillis();

        DoggedCubeBuilder doggedBuilder = new DoggedCubeBuilder(cube.getDescriptor(), dictionaryMap);
        doggedBuilder.setConcurrentThreads(THREADS);

        {
            Future<?> future = executorService.submit(doggedBuilder.buildAsRunnable(queue, new NoopWriter()));
            InMemCubeBuilderTest.feedData(cube, flatTable, queue, INPUT_ROWS, randSeed);
            future.get();
        }
    }

    class NoopWriter implements ICuboidWriter {
        @Override
        public void write(long cuboidId, GTRecord record) throws IOException {
        }
    }
}