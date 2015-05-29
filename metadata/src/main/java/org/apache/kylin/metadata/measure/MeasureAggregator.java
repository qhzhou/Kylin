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

package org.apache.kylin.metadata.measure;

import org.apache.kylin.metadata.model.DataType;
import org.apache.kylin.metadata.model.FunctionDesc;

/**
 * @author yangli9
 * 
 */
abstract public class MeasureAggregator<V> {

    public static MeasureAggregator<?> create(String funcName, String returnType) {
        if (FunctionDesc.FUNC_SUM.equalsIgnoreCase(funcName) || FunctionDesc.FUNC_COUNT.equalsIgnoreCase(funcName)) {
            if (isInteger(returnType))
                return new LongSumAggregator();
            else if (isBigDecimal(returnType))
                return new BigDecimalSumAggregator();
            else if (isDouble(returnType))
                return new DoubleSumAggregator();
        } else if (FunctionDesc.FUNC_COUNT_DISTINCT.equalsIgnoreCase(funcName)) {
            DataType hllcType = DataType.getInstance(returnType);
            if (hllcType.isHLLC())
                return new HLLCAggregator(hllcType.getPrecision());
            else
                return new LDCAggregator();
        } else if (FunctionDesc.FUNC_MAX.equalsIgnoreCase(funcName)) {
            if (isInteger(returnType))
                return new LongMaxAggregator();
            else if (isBigDecimal(returnType))
                return new BigDecimalMaxAggregator();
            else if (isDouble(returnType))
                return new DoubleMaxAggregator();
        } else if (FunctionDesc.FUNC_MIN.equalsIgnoreCase(funcName)) {
            if (isInteger(returnType))
                return new LongMinAggregator();
            else if (isBigDecimal(returnType))
                return new BigDecimalMinAggregator();
            else if (isDouble(returnType))
                return new DoubleMinAggregator();
        }
        throw new IllegalArgumentException("No aggregator for func '" + funcName + "' and return type '" + returnType + "'");
    }

    public static boolean isBigDecimal(String type) {
        return type.startsWith("decimal");
    }

    public static boolean isDouble(String type) {
        return "double".equalsIgnoreCase(type) || "float".equalsIgnoreCase(type) || "real".equalsIgnoreCase(type);
    }

    public static boolean isInteger(String type) {
        return "long".equalsIgnoreCase(type) || "bigint".equalsIgnoreCase(type) || "int".equalsIgnoreCase(type) || "integer".equalsIgnoreCase(type);
    }

    public static int guessBigDecimalMemBytes() {
        // 116 returned by AggregationCacheMemSizeTest
        return 8 // aggregator obj shell
        + 8 // ref to BigDecimal
        + 8 // BigDecimal obj shell
        + 100; // guess of BigDecimal internal
    }

    public static int guessDoubleMemBytes() {
        // 29 to 44 returned by AggregationCacheMemSizeTest
        return 44;
        /*
        return 8 // aggregator obj shell
        + 8 // ref to DoubleWritable
        + 8 // DoubleWritable obj shell
        + 8; // size of double
        */
    }

    public static int guessLongMemBytes() {
        // 29 to 44 returned by AggregationCacheMemSizeTest
        return 44;
        /*
        return 8 // aggregator obj shell
        + 8 // ref to LongWritable
        + 8 // LongWritable obj shell
        + 8; // size of long
        */
    }

    // ============================================================================

    @SuppressWarnings("rawtypes")
    public void setDependentAggregator(MeasureAggregator agg) {
    }

    abstract public void reset();

    abstract public void aggregate(V value);

    abstract public V getState();

    // get an estimate of memory consumption UPPER BOUND
    abstract public int getMemBytesEstimate();
}
