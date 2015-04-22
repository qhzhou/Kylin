/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.apache.kylin.common.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;

import com.google.common.base.Function;
import com.google.common.collect.Lists;

import com.google.common.collect.Ordering;
import com.google.common.collect.Range;
import com.google.common.collect.Ranges;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.lang.math.IntRange;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.Ignore;
import org.junit.Test;
import org.slf4j.LoggerFactory;

/**
* Created by honma on 10/17/14.
* <p/>
* Keep this test case to test basic java functionality
* development concept proving use
*/
@Ignore("convenient trial tool for dev")
@SuppressWarnings("unused")
public class BasicTest {
    protected static final org.slf4j.Logger logger = LoggerFactory.getLogger(BasicTest.class);

    private void log(ByteBuffer a) {
        Integer x = 4;
        foo(x);
    }

    private void foo(Long a) {
        System.out.printf("a");
    }

    private void foo(Integer b) {
        System.out.printf("b");
    }

    private enum MetricType {
        Count, DimensionAsMetric, DistinctCount, Normal
    }

    @Test
    @Ignore("convenient trial tool for dev")
    public void test1() throws Exception {


        System.out.println(Ranges.open(3, 5).isConnected(Ranges.open(4, 10)));
        System.out.println(Ranges.open(4, 10).isConnected(Ranges.open(3,5)));


        String bb = "\\x00\\x00\\x00\\x00\\x01\\x3F\\xD0\\x2D\\58\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00";//2013/07/12 07:59:37
        String cc = "\\x00\\x00\\x00\\x00\\x01\\x41\\xBE\\x8F\\xD8\\x00\\x00\\x00\\x00\\x00\\x00\\x00";//2013/10/16 08:00:00
        String dd = "\\x00\\x00\\x00\\x00\\x01\\x41\\xBE\\x8F\\xD8\\x07\\x00\\x18\\x00\\x00\\x00";

        byte[] bytes = BytesUtil.fromReadableText(dd);
        long ttt = BytesUtil.readLong(bytes, 2, 8);
        System.out.println(time(ttt));

        System.out.println("\\");
        System.out.println("n");

        System.out.println("The start key is set to " + null);
        System.out.println(time(946684800000L));
        long current = System.currentTimeMillis();
        System.out.println(time(current));

        Calendar a = Calendar.getInstance();
        Calendar b = Calendar.getInstance();
        Calendar c = Calendar.getInstance();
        b.clear();
        c.clear();

        System.out.println(time(b.getTimeInMillis()));
        System.out.println(time(c.getTimeInMillis()));

        a.setTimeInMillis(current);
        b.set(a.get(Calendar.YEAR), a.get(Calendar.MONTH), a.get(Calendar.DAY_OF_MONTH), a.get(Calendar.HOUR_OF_DAY), a.get(Calendar.MINUTE));
        c.set(a.get(Calendar.YEAR), a.get(Calendar.MONTH), a.get(Calendar.DAY_OF_MONTH), a.get(Calendar.HOUR_OF_DAY), 0);

        System.out.println(time(b.getTimeInMillis()));
        System.out.println(time(c.getTimeInMillis()));

    }

    @Test
    @Ignore("fix it later")
    public void test2() throws IOException, ConfigurationException {
        ArrayList<String> x = Lists.newArrayListWithCapacity(10);
        x.set(2, "dd");
        for (String y : x) {
            System.out.println(y);
        }
    }

    private static String time(long t) {
        DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(t);
        return dateFormat.format(cal.getTime());
    }
}
