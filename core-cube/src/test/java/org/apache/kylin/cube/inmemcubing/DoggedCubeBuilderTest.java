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

package org.apache.kylin.cube.inmemcubing;

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
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
import org.apache.kylin.cube.inmemcubing.DoggedCubeBuilder;
import org.apache.kylin.cube.inmemcubing.ICuboidWriter;
import org.apache.kylin.cube.inmemcubing.InMemCubeBuilder;
import org.apache.kylin.dict.Dictionary;
import org.apache.kylin.gridtable.GTRecord;
import org.apache.kylin.metadata.model.TblColRef;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 */
public class DoggedCubeBuilderTest extends LocalFileMetadataTestCase {

    @SuppressWarnings("unused")
    private static final Logger logger = LoggerFactory.getLogger(DoggedCubeBuilderTest.class);

    private static final int INPUT_ROWS = 10000;
    private static final int SPLIT_ROWS = 5000;
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
        doggedBuilder.setSplitRowThreshold(SPLIT_ROWS);
        FileRecordWriter doggedResult = new FileRecordWriter();

        {
            Future<?> future = executorService.submit(doggedBuilder.buildAsRunnable(queue, doggedResult));
            InMemCubeBuilderTest.feedData(cube, flatTable, queue, INPUT_ROWS, randSeed);
            future.get();
            doggedResult.close();
        }

        InMemCubeBuilder inmemBuilder = new InMemCubeBuilder(cube.getDescriptor(), dictionaryMap);
        inmemBuilder.setConcurrentThreads(THREADS);
        FileRecordWriter inmemResult = new FileRecordWriter();

        {
            Future<?> future = executorService.submit(inmemBuilder.buildAsRunnable(queue, inmemResult));
            InMemCubeBuilderTest.feedData(cube, flatTable, queue, INPUT_ROWS, randSeed);
            future.get();
            inmemResult.close();
        }

        fileCompare(doggedResult.file, inmemResult.file);
        doggedResult.file.delete();
        inmemResult.file.delete();
    }

    private void fileCompare(File file, File file2) throws IOException {
        BufferedReader r1 = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
        BufferedReader r2 = new BufferedReader(new InputStreamReader(new FileInputStream(file2), "UTF-8"));

        String line1, line2;
        do {
            line1 = r1.readLine();
            line2 = r2.readLine();
            
            assertEquals(line1, line2);
            
        } while (line1 != null || line2 != null);

        r1.close();
        r2.close();
    }

    class FileRecordWriter implements ICuboidWriter {

        File file;
        PrintWriter writer;

        FileRecordWriter() throws IOException {
            file = File.createTempFile("DoggedCubeBuilderTest_", ".data");
            writer = new PrintWriter(file, "UTF-8");
        }

        @Override
        public void write(long cuboidId, GTRecord record) throws IOException {
            writer.print(cuboidId);
            writer.print(", ");
            writer.print(record.toString());
            writer.println();
        }

        public void close() {
            writer.close();
        }
    }
}