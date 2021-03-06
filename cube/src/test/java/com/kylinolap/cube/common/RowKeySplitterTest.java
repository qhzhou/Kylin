/*
 * Copyright 2013-2014 eBay Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.kylinolap.cube.common;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.kylinolap.common.util.LocalFileMetadataTestCase;
import com.kylinolap.cube.CubeInstance;
import com.kylinolap.cube.CubeManager;
import com.kylinolap.metadata.MetadataManager;

/**
 * @author George Song (ysong1)
 * 
 */
public class RowKeySplitterTest extends LocalFileMetadataTestCase {

    @Before
    public void setUp() throws Exception {
        this.createTestMetadata();
        MetadataManager.removeInstance(this.getTestConfig());
    }

    @After
    public void after() throws Exception {
        this.cleanupTestMetadata();
    }

    @Test
    public void testWithSlr() throws Exception {
        CubeInstance cube = CubeManager.getInstance(this.getTestConfig()).getCube("TEST_KYLIN_CUBE_WITH_SLR_READY");

        RowKeySplitter rowKeySplitter = new RowKeySplitter(cube.getFirstSegment(), 10, 20);
        // base cuboid rowkey
        byte[] input = { 0, 0, 0, 0, 0, 0, 1, -1, 49, 48, 48, 48, 48, 48, 48, 48, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 11, 54, -105, 55, 13, 71, 114, 65, 66, 73, 78, 9, 9, 9, 9, 9, 9, 9, 9, 0, 10, 0 };
        rowKeySplitter.split(input, input.length);

        assertEquals(10, rowKeySplitter.getBufferSize());
    }

    @Test
    public void testWithoutSlr() throws Exception {
        CubeInstance cube = CubeManager.getInstance(this.getTestConfig()).getCube("TEST_KYLIN_CUBE_WITHOUT_SLR_READY");

        RowKeySplitter rowKeySplitter = new RowKeySplitter(cube.getFirstSegment(), 10, 20);
        // base cuboid rowkey
        byte[] input = { 0, 0, 0, 0, 0, 0, 0, -1, 11, 55, -13, 13, 22, 34, 121, 70, 80, 45, 71, 84, 67, 9, 9, 9, 9, 9, 9, 0, 10, 5 };
        rowKeySplitter.split(input, input.length);

        assertEquals(9, rowKeySplitter.getBufferSize());
    }
}
