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

package org.apache.kylin.storage.gridtable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import org.apache.kylin.common.util.DateFormat;
import org.apache.kylin.common.util.ImmutableBitSet;
import org.apache.kylin.metadata.measure.LongMutable;
import org.apache.kylin.metadata.model.DataType;
import org.apache.kylin.storage.gridtable.GTInfo.Builder;

public class UnitTestSupport {

    public static GTInfo basicInfo() {
        Builder builder = infoBuilder();
        GTInfo info = builder.build();
        return info;
    }

    public static GTInfo advancedInfo() {
        Builder builder = infoBuilder();
        builder.enableColumnBlock(new ImmutableBitSet[] { setOf(0), setOf(1, 2), setOf(3, 4) });
        builder.enableRowBlock(4);
        GTInfo info = builder.build();
        return info;
    }

    private static Builder infoBuilder() {
        Builder builder = GTInfo.builder();
        builder.setCodeSystem(new GTSampleCodeSystem());
        builder.setColumns( //
                DataType.getInstance("varchar(10)"), //
                DataType.getInstance("varchar(10)"), //
                DataType.getInstance("varchar(10)"), //
                DataType.getInstance("bigint"), //
                DataType.getInstance("decimal") //
        );
        builder.setPrimaryKey(setOf(0));
        builder.setColumnPreferIndex(setOf(0));
        return builder;
    }

    public static List<GTRecord> mockupData(GTInfo info, int nRows) {
        List<GTRecord> result = new ArrayList<GTRecord>(nRows);
        int round = nRows / 10;
        for (int i = 0; i < round; i++) {
            String d_01_14 = datePlus("2015-01-14", i * 4);
            String d_01_15 = datePlus("2015-01-15", i * 4);
            String d_01_16 = datePlus("2015-01-16", i * 4);
            String d_01_17 = datePlus("2015-01-17", i * 4);
            result.add(newRec(info, d_01_14, "Yang", "Food", new LongMutable(10), new BigDecimal("10.5")));
            result.add(newRec(info, d_01_14, "Luke", "Food", new LongMutable(10), new BigDecimal("10.5")));
            result.add(newRec(info, d_01_15, "Xu", "Food", new LongMutable(10), new BigDecimal("10.5")));
            result.add(newRec(info, d_01_15, "Dong", "Food", new LongMutable(10), new BigDecimal("10.5")));
            result.add(newRec(info, d_01_15, "Jason", "Food", new LongMutable(10), new BigDecimal("10.5")));
            result.add(newRec(info, d_01_16, "Mahone", "Food", new LongMutable(10), new BigDecimal("10.5")));
            result.add(newRec(info, d_01_16, "Shaofeng", "Food", new LongMutable(10), new BigDecimal("10.5")));
            result.add(newRec(info, d_01_16, "Qianhao", "Food", new LongMutable(10), new BigDecimal("10.5")));
            result.add(newRec(info, d_01_16, "George", "Food", new LongMutable(10), new BigDecimal("10.5")));
            result.add(newRec(info, d_01_17, "Kejia", "Food", new LongMutable(10), new BigDecimal("10.5")));
        }
        return result;
    }
    
    private static String datePlus(String date, int plusDays) {
        long millis = DateFormat.stringToMillis(date);
        millis += (1000L * 3600L * 24L) * plusDays;
        return DateFormat.formatToDateStr(millis);
    }

    private static GTRecord newRec(GTInfo info, String date, String name, String category, LongMutable amount, BigDecimal price) {
        GTRecord rec = new GTRecord(info);
        return rec.setValues(date, name, category, amount, price);
    }

    private static ImmutableBitSet setOf(int... values) {
        BitSet set = new BitSet();
        for (int i : values)
            set.set(i);
        return new ImmutableBitSet(set);
    }
}
