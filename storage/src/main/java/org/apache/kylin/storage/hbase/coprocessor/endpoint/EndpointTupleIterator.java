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

package org.apache.kylin.storage.hbase.coprocessor.endpoint;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.NotImplementedException;
import org.apache.commons.lang3.SerializationUtils;
import org.apache.hadoop.hbase.client.HConnection;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.coprocessor.Batch;
import org.apache.hadoop.hbase.ipc.BlockingRpcCallback;
import org.apache.hadoop.hbase.ipc.ServerRpcController;
import org.apache.kylin.common.util.RangeUtil;
import org.apache.kylin.invertedindex.IISegment;
import org.apache.kylin.invertedindex.index.TableRecord;
import org.apache.kylin.invertedindex.index.TableRecordInfo;
import org.apache.kylin.metadata.filter.ConstantTupleFilter;
import org.apache.kylin.metadata.filter.TupleFilter;
import org.apache.kylin.metadata.model.DataType;
import org.apache.kylin.metadata.model.FunctionDesc;
import org.apache.kylin.metadata.model.TblColRef;
import org.apache.kylin.metadata.tuple.ITuple;
import org.apache.kylin.metadata.tuple.ITupleIterator;
import org.apache.kylin.storage.StorageContext;
import org.apache.kylin.storage.hbase.coprocessor.CoprocessorFilter;
import org.apache.kylin.storage.hbase.coprocessor.CoprocessorProjector;
import org.apache.kylin.storage.hbase.coprocessor.CoprocessorRowType;
import org.apache.kylin.storage.hbase.coprocessor.FilterDecorator;
import org.apache.kylin.storage.hbase.coprocessor.endpoint.generated.IIProtos;
import org.apache.kylin.storage.tuple.Tuple;
import org.apache.kylin.storage.tuple.TupleInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Range;
import com.google.common.collect.Ranges;
import com.google.common.collect.Sets;
import com.google.protobuf.ByteString;

/**
 * Created by Hongbin Ma(Binmahone) on 12/2/14.
 */
public class EndpointTupleIterator implements ITupleIterator {

    private final static Logger logger = LoggerFactory.getLogger(EndpointTupleIterator.class);

    private final IISegment seg;

    private final String factTableName;
    private final List<TblColRef> columns;
    private final TupleInfo tupleInfo;
    private final TableRecordInfo tableRecordInfo;
    private final EndpointTupleConverter tupleConverter;

    private final CoprocessorRowType pushedDownRowType;
    private final CoprocessorFilter pushedDownFilter;
    private final CoprocessorProjector pushedDownProjector;
    private final EndpointAggregators pushedDownAggregators;
    private final Range<Long> tsRange;//timestamp column condition's interval

    private Iterator<List<IIProtos.IIResponse.IIRow>> regionResponsesIterator = null;
    private ITupleIterator tupleIterator = null;
    private HTableInterface table = null;

    private TblColRef partitionCol;
    private long lastDataTime;
    private int rowsInAllMetric = 0;

    public EndpointTupleIterator(IISegment segment, TupleFilter rootFilter, Collection<TblColRef> groupBy, List<FunctionDesc> measures, StorageContext context, HConnection conn, TupleInfo returnTupleInfo) throws Throwable {

        String tableName = segment.getStorageLocationIdentifier();
        table = conn.getTable(tableName);
        factTableName = segment.getIIDesc().getFactTableName();

        if (rootFilter == null) {
            rootFilter = ConstantTupleFilter.TRUE;
        }

        if (groupBy == null) {
            groupBy = Sets.newHashSet();
        }

        if (measures == null) {
            measures = Lists.newArrayList();
        }

        //this method will change measures
        rewriteMeasureParameters(measures, segment.getColumns());

        this.seg = segment;
        this.columns = segment.getColumns();

        this.tupleInfo = returnTupleInfo;
        this.tupleConverter = new EndpointTupleConverter(columns, measures, returnTupleInfo);
        this.tableRecordInfo = new TableRecordInfo(this.seg);

        this.pushedDownRowType = CoprocessorRowType.fromTableRecordInfo(tableRecordInfo, this.columns);
        this.pushedDownFilter = CoprocessorFilter.fromFilter(new ClearTextDictionary(this.tableRecordInfo), rootFilter, FilterDecorator.FilterConstantsTreatment.AS_IT_IS);

        for (TblColRef column : this.pushedDownFilter.getInevaluableColumns()) {
            groupBy.add(column);
        }

        this.pushedDownProjector = CoprocessorProjector.makeForEndpoint(tableRecordInfo, groupBy);
        this.pushedDownAggregators = EndpointAggregators.fromFunctions(tableRecordInfo, measures);

        int tsCol = this.tableRecordInfo.getTimestampColumn();
        this.partitionCol = this.columns.get(tsCol);
        this.tsRange = TsConditionExtractor.extractTsCondition(this.partitionCol, rootFilter);

        if (this.tsRange == null) {
            logger.info("TsRange conflict for endpoint, return empty directly");
            this.tupleIterator = ITupleIterator.EMPTY_TUPLE_ITERATOR;
        } else {
            logger.info("The tsRange being pushed is " + RangeUtil.formatTsRange(tsRange));
        }

        IIProtos.IIRequest endpointRequest = prepareRequest();

        Collection<IIProtos.IIResponse> shardResults = getResults(endpointRequest, table);

        this.lastDataTime = Collections.min(Collections2.transform(shardResults, new Function<IIProtos.IIResponse, Long>() {
            @Nullable
            @Override
            public Long apply(IIProtos.IIResponse input) {
                return input.getLatestDataTime();
            }
        }));

        this.regionResponsesIterator = Collections2.transform(shardResults, new Function<IIProtos.IIResponse, List<IIProtos.IIResponse.IIRow>>() {
            @Nullable
            @Override
            public List<IIProtos.IIResponse.IIRow> apply(@Nullable IIProtos.IIResponse input) {
                return input.getRowsList();
            }
        }).iterator();

        if (this.regionResponsesIterator.hasNext()) {
            this.tupleIterator = new SingleRegionTupleIterator(this.regionResponsesIterator.next());
        } else {
            this.tupleIterator = ITupleIterator.EMPTY_TUPLE_ITERATOR;
        }
    }

    /**
     * measure comes from query engine, does not contain enough information
     */
    private void rewriteMeasureParameters(List<FunctionDesc> measures, List<TblColRef> columns) {
        for (FunctionDesc functionDesc : measures) {
            if (functionDesc.isCount()) {
                functionDesc.setReturnType("bigint");
                functionDesc.setReturnDataType(DataType.getInstance(functionDesc.getReturnType()));
            } else {
                boolean updated = false;
                for (TblColRef column : columns) {
                    if (column.isSameAs(factTableName, functionDesc.getParameter().getValue())) {
                        if (functionDesc.isCountDistinct()) {
                            //TODO: default precision might need be configurable
                            String iiDefaultHLLC = "hllc10";
                            functionDesc.setReturnType(iiDefaultHLLC);
                            functionDesc.setReturnDataType(DataType.getInstance(iiDefaultHLLC));
                        } else {
                            functionDesc.setReturnType(column.getColumn().getType().toString());
                            functionDesc.setReturnDataType(DataType.getInstance(functionDesc.getReturnType()));
                        }
                        functionDesc.getParameter().setColRefs(ImmutableList.of(column));
                        updated = true;
                        break;
                    }
                }
                if (!updated) {
                    throw new RuntimeException("Func " + functionDesc + " is not related to any column in fact table " + factTableName);
                }
            }
        }
    }

    @Override
    public boolean hasNext() {
        while (!this.tupleIterator.hasNext()) {
            if (this.regionResponsesIterator.hasNext()) {
                this.tupleIterator = new SingleRegionTupleIterator(this.regionResponsesIterator.next());
            } else {
                return false;
            }
        }
        return true;
    }

    @Override
    public ITuple next() {
        rowsInAllMetric++;

        if (!hasNext()) {
            throw new IllegalStateException("No more ITuple in EndpointTupleIterator");
        }

        ITuple tuple = this.tupleIterator.next();
        return tuple;
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException();

    }

    @Override
    public void close() {
        IOUtils.closeQuietly(table);
        logger.info("Closed after " + rowsInAllMetric + " rows are fetched");
    }

    @Override
    public Range<Long> getCacheExcludedPeriod() {
        return Ranges.greaterThan(lastDataTime );
    }

    private IIProtos.IIRequest prepareRequest() throws IOException {
        IIProtos.IIRequest.Builder builder = IIProtos.IIRequest.newBuilder();

        if (this.tsRange != null) {
            byte[] tsRangeBytes = SerializationUtils.serialize(this.tsRange);
            builder.setTsRange(ByteString.copyFrom(tsRangeBytes));
        }

        builder.setType(ByteString.copyFrom(CoprocessorRowType.serialize(pushedDownRowType))) //
                .setFilter(ByteString.copyFrom(CoprocessorFilter.serialize(pushedDownFilter))) //
                .setProjector(ByteString.copyFrom(CoprocessorProjector.serialize(pushedDownProjector))) //
                .setAggregator(ByteString.copyFrom(EndpointAggregators.serialize(pushedDownAggregators)));

        IIProtos.IIRequest request = builder.build();

        return request;
    }

    //TODO : async callback
    private Collection<IIProtos.IIResponse> getResults(final IIProtos.IIRequest request, HTableInterface table) throws Throwable {
        Map<byte[], IIProtos.IIResponse> results = table.coprocessorService(IIProtos.RowsService.class, null, null, new Batch.Call<IIProtos.RowsService, IIProtos.IIResponse>() {
            public IIProtos.IIResponse call(IIProtos.RowsService rowsService) throws IOException {
                ServerRpcController controller = new ServerRpcController();
                BlockingRpcCallback<IIProtos.IIResponse> rpcCallback = new BlockingRpcCallback<>();
                rowsService.getRows(controller, request, rpcCallback);
                IIProtos.IIResponse response = rpcCallback.get();
                if (controller.failedOnException()) {
                    throw controller.getFailedOn();
                }

                return response;
            }
        });

        return results.values();
    }

    /**
     * Internal class to handle iterators for a single region's returned rows
     */
    class SingleRegionTupleIterator implements ITupleIterator {
        private List<IIProtos.IIResponse.IIRow> rows;
        private int index = 0;

        //not thread safe!
        private TableRecord tableRecord;
        private List<Object> measureValues;
        private Tuple tuple;

        public SingleRegionTupleIterator(List<IIProtos.IIResponse.IIRow> rows) {
            this.rows = rows;
            this.index = 0;
            this.tableRecord = tableRecordInfo.createTableRecord();
            this.tuple = new Tuple(tupleInfo);
        }

        @Override
        public boolean hasNext() {
            return index < rows.size();
        }

        @Override
        public ITuple next() {
            if (!hasNext()) {
                throw new IllegalStateException("No more Tuple in the SingleRegionTupleIterator");
            }

            IIProtos.IIResponse.IIRow currentRow = rows.get(index);
            byte[] columnsBytes = currentRow.getColumns().toByteArray();
            this.tableRecord.setBytes(columnsBytes, 0, columnsBytes.length);
            if (currentRow.hasMeasures()) {
                byte[] measuresBytes = currentRow.getMeasures().toByteArray();

                this.measureValues = pushedDownAggregators.deserializeMetricValues(measuresBytes, 0);
            }

            index++;
            
            return tupleConverter.makeTuple(this.tableRecord, this.measureValues, this.tuple);
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }

        @Override
        public Range<Long> getCacheExcludedPeriod() {
            throw new NotImplementedException();
        }
    }
}
