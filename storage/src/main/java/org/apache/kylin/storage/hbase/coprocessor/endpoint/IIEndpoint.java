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
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.SerializationUtils;
import org.apache.hadoop.hbase.Coprocessor;
import org.apache.hadoop.hbase.CoprocessorEnvironment;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.coprocessor.CoprocessorException;
import org.apache.hadoop.hbase.coprocessor.CoprocessorService;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.protobuf.ResponseConverter;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.hadoop.hbase.regionserver.RegionScanner;
import org.apache.kylin.common.util.Array;
import org.apache.kylin.common.util.BytesUtil;
import org.apache.kylin.cube.kv.RowKeyColumnIO;
import org.apache.kylin.dict.Dictionary;
import org.apache.kylin.dict.TrieDictionary;
import org.apache.kylin.invertedindex.index.RawTableRecord;
import org.apache.kylin.invertedindex.index.Slice;
import org.apache.kylin.invertedindex.index.TableRecordInfoDigest;
import org.apache.kylin.invertedindex.model.IIDesc;
import org.apache.kylin.invertedindex.model.IIKeyValueCodec;
import org.apache.kylin.metadata.measure.MeasureAggregator;
import org.apache.kylin.metadata.model.TblColRef;
import org.apache.kylin.storage.filter.BitMapFilterEvaluator;
import org.apache.kylin.storage.hbase.coprocessor.*;
import org.apache.kylin.storage.hbase.coprocessor.endpoint.generated.IIProtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.collect.Range;
import com.google.protobuf.ByteString;
import com.google.protobuf.RpcCallback;
import com.google.protobuf.RpcController;
import com.google.protobuf.Service;
import it.uniroma3.mat.extendedset.intset.ConciseSet;

/**
 * Created by honma on 11/7/14.
 */
public class IIEndpoint extends IIProtos.RowsService implements Coprocessor, CoprocessorService {

    private static final Logger logger = LoggerFactory.getLogger(IIEndpoint.class);
    private static final int MEMORY_LIMIT = 500 * 1024 * 1024;

    private RegionCoprocessorEnvironment env;

    public IIEndpoint() {
    }

    private Scan prepareScan(IIProtos.IIRequest request, HRegion region) throws IOException {
        Scan scan = new Scan();
        scan.addColumn(IIDesc.HBASE_FAMILY_BYTES, IIDesc.HBASE_QUALIFIER_BYTES);
        scan.addColumn(IIDesc.HBASE_FAMILY_BYTES, IIDesc.HBASE_DICTIONARY_BYTES);

        int shard;

        if (request.hasTsRange()) {
            Range<Long> tsRange = (Range<Long>) SerializationUtils.deserialize(request.getTsRange().toByteArray());
            byte[] regionStartKey = region.getStartKey();
            if (!ArrayUtils.isEmpty(regionStartKey)) {
                shard = BytesUtil.readUnsigned(regionStartKey, 0, IIKeyValueCodec.SHARD_LEN);
            } else {
                shard = 0;
            }
            logger.info("Start key of the region is: " + BytesUtil.toReadableText(regionStartKey) + ", making shard to be :" + shard);

            if (tsRange.hasLowerBound()) {
                Preconditions.checkArgument(shard != -1, "Shard is -1!");
                long tsStart = tsRange.lowerEndpoint();
                logger.info("ts start is " + tsStart);

                byte[] idealStartKey = new byte[IIKeyValueCodec.SHARD_LEN + IIKeyValueCodec.TIMEPART_LEN];
                BytesUtil.writeUnsigned(shard, idealStartKey, 0, IIKeyValueCodec.SHARD_LEN);
                BytesUtil.writeLong(tsStart, idealStartKey, IIKeyValueCodec.SHARD_LEN, IIKeyValueCodec.TIMEPART_LEN);
                logger.info("ideaStartKey is(readable) :" + BytesUtil.toReadableText(idealStartKey));
                Result result = region.getClosestRowBefore(idealStartKey, IIDesc.HBASE_FAMILY_BYTES);
                if (result != null) {
                    byte[] actualStartKey = Arrays.copyOf(result.getRow(), IIKeyValueCodec.SHARD_LEN + IIKeyValueCodec.TIMEPART_LEN);
                    scan.setStartRow(actualStartKey);
                    logger.info("The start key is set to " + BytesUtil.toReadableText(actualStartKey));
                } else {
                    logger.info("There is no key before ideaStartKey so ignore tsStart");
                }
            }

            if (tsRange.hasUpperBound()) {
                Preconditions.checkArgument(shard != -1, "Shard is -1");
                long tsEnd = tsRange.upperEndpoint();
                logger.info("ts end is " + tsEnd);

                byte[] actualEndKey = new byte[IIKeyValueCodec.SHARD_LEN + IIKeyValueCodec.TIMEPART_LEN];
                BytesUtil.writeUnsigned(shard, actualEndKey, 0, IIKeyValueCodec.SHARD_LEN);
                BytesUtil.writeLong(tsEnd + 1, actualEndKey, IIKeyValueCodec.SHARD_LEN, IIKeyValueCodec.TIMEPART_LEN);//notice +1 here
                scan.setStopRow(actualEndKey);
                logger.info("The stop key is set to " + BytesUtil.toReadableText(actualEndKey));
            }
        }

        return scan;
    }

    //TODO: protobuf does not provide built-in compression
    @Override
    public void getRows(RpcController controller, IIProtos.IIRequest request, RpcCallback<IIProtos.IIResponse> done) {

        RegionScanner innerScanner = null;
        HRegion region = null;

        try {
            region = env.getRegion();
            region.startRegionOperation();

            innerScanner = region.getScanner(prepareScan(request, region));

            CoprocessorRowType type = CoprocessorRowType.deserialize(request.getType().toByteArray());
            CoprocessorProjector projector = CoprocessorProjector.deserialize(request.getProjector().toByteArray());
            EndpointAggregators aggregators = EndpointAggregators.deserialize(request.getAggregator().toByteArray());
            CoprocessorFilter filter = CoprocessorFilter.deserialize(request.getFilter().toByteArray());

            IIProtos.IIResponse response = getResponse(innerScanner, type, projector, aggregators, filter);
            done.run(response);
        } catch (IOException ioe) {
            logger.error(ioe.toString());
            ResponseConverter.setControllerException(controller, ioe);
        } finally {
            IOUtils.closeQuietly(innerScanner);
            if (region != null) {
                try {
                    region.closeRegionOperation();
                } catch (IOException e) {
                    e.printStackTrace();
                    throw new RuntimeException(e);
                }
            }
        }
    }

    public IIProtos.IIResponse getResponse(RegionScanner innerScanner, CoprocessorRowType type, CoprocessorProjector projector, EndpointAggregators aggregators, CoprocessorFilter filter) {

        TableRecordInfoDigest tableRecordInfoDigest = aggregators.getTableRecordInfoDigest();

        IIProtos.IIResponse response;

        synchronized (innerScanner) {
            IIKeyValueCodec codec = new IIKeyValueCodec(tableRecordInfoDigest);
            //TODO pass projector to codec to skip loading columns
            Iterable<Slice> slices = codec.decodeKeyValue(new HbaseServerKVIterator(innerScanner));

            if (aggregators.isEmpty()) {
                response = getNonAggregatedResponse(slices, tableRecordInfoDigest, filter, type);
            } else {
                response = getAggregatedResponse(slices, tableRecordInfoDigest, filter, type, projector, aggregators);
            }
        }
        return response;
    }

    //TODO check current memory checking is good enough
    private IIProtos.IIResponse getAggregatedResponse(Iterable<Slice> slices, TableRecordInfoDigest recordInfo, CoprocessorFilter filter, CoprocessorRowType type, CoprocessorProjector projector, EndpointAggregators aggregators) {
        EndpointAggregationCache aggCache = new EndpointAggregationCache(aggregators);
        IIProtos.IIResponse.Builder responseBuilder = IIProtos.IIResponse.newBuilder();
        ClearTextDictionary clearTextDictionary = new ClearTextDictionary(recordInfo, type);
        RowKeyColumnIO rowKeyColumnIO = new RowKeyColumnIO(clearTextDictionary);

        byte[] recordBuffer = new byte[recordInfo.getByteFormLen()];
        final byte[] buffer = new byte[CoprocessorConstants.METRIC_SERIALIZE_BUFFER_SIZE];

        int iteratedSliceCount = 0;
        for (Slice slice : slices) {
            iteratedSliceCount++;

            //TODO localdict
            //dictionaries for fact table columns can not be determined while streaming.
            //a piece of dict coincide with each Slice, we call it "local dict"
            final Dictionary<?>[] localDictionaries = slice.getLocalDictionaries();
            CoprocessorFilter newFilter;
            final boolean emptyDictionary = Array.isEmpty(localDictionaries);
            logger.info("empty dictionary:" + emptyDictionary);
            if (emptyDictionary) {
                newFilter = filter;
            } else {
                for (Dictionary<?> localDictionary : localDictionaries) {
                    if (localDictionary instanceof TrieDictionary) {
                        ((TrieDictionary) localDictionary).enableIdToValueBytesCache();
                    }
                }
                newFilter = CoprocessorFilter.fromFilter(new LocalDictionary(localDictionaries, type, slice.getInfo()), filter.getFilter(), FilterDecorator.FilterConstantsTreatment.REPLACE_WITH_LOCAL_DICT);
            }

            ConciseSet result = null;
            if (filter != null) {
                result = new BitMapFilterEvaluator(new SliceBitMapProvider(slice, type)).evaluate(newFilter.getFilter());
            }

            Iterator<RawTableRecord> iterator = slice.iterateWithBitmap(result);
            while (iterator.hasNext()) {
                final RawTableRecord rawTableRecord = iterator.next();
                decodeWithDictionary(recordBuffer, rawTableRecord, localDictionaries, recordInfo, rowKeyColumnIO, type);
                AggrKey aggKey = projector.getAggrKey(recordBuffer);
                MeasureAggregator[] bufs = aggCache.getBuffer(aggKey);
                aggregators.aggregate(bufs, recordBuffer);
                aggCache.checkMemoryUsage();
            }
        }

        logger.info("Iterated Slices count: " + iteratedSliceCount);

        for (Map.Entry<AggrKey, MeasureAggregator[]> entry : aggCache.getAllEntries()) {
            AggrKey aggrKey = entry.getKey();
            IIProtos.IIResponse.IIRow.Builder rowBuilder = IIProtos.IIResponse.IIRow.newBuilder().setColumns(ByteString.copyFrom(aggrKey.get(), aggrKey.offset(), aggrKey.length()));
            int length = aggregators.serializeMetricValues(entry.getValue(), buffer);
            rowBuilder.setMeasures(ByteString.copyFrom(buffer, 0, length));
            responseBuilder.addRows(rowBuilder.build());
        }

        return responseBuilder.build();
    }

    private void decodeWithDictionary(byte[] recordBuffer, RawTableRecord encodedRecord, Dictionary<?>[] localDictionaries, TableRecordInfoDigest digest, RowKeyColumnIO rowKeyColumnIO, CoprocessorRowType coprocessorRowType) {
        final TblColRef[] columns = coprocessorRowType.columns;
        final int columnSize = columns.length;
        final boolean[] isMetric = digest.isMetrics();
        final boolean emptyDictionary = Array.isEmpty(localDictionaries);
        for (int i = 0; i < columnSize; i++) {
            final TblColRef column = columns[i];
            if (isMetric[i]) {
                rowKeyColumnIO.writeColumnWithoutDictionary(encodedRecord.getBytes(), encodedRecord.offset(i), encodedRecord.length(i), recordBuffer, digest.offset(i), rowKeyColumnIO.getColumnLength(column));
            } else {
                if (emptyDictionary) {
                    rowKeyColumnIO.writeColumnWithoutDictionary(encodedRecord.getBytes(), encodedRecord.offset(i), encodedRecord.length(i), recordBuffer, digest.offset(i), rowKeyColumnIO.getColumnLength(column));
                } else {
                    final Dictionary<?> localDictionary = localDictionaries[i];
                    final byte[] valueBytesFromId = localDictionary.getValueBytesFromId(encodedRecord.getValueID(i));
                    rowKeyColumnIO.writeColumnWithoutDictionary(valueBytesFromId, 0, valueBytesFromId.length, recordBuffer, digest.offset(i), rowKeyColumnIO.getColumnLength(column));
                }
            }
        }
    }

    private IIProtos.IIResponse getNonAggregatedResponse(Iterable<Slice> slices, TableRecordInfoDigest recordInfo, CoprocessorFilter filter, CoprocessorRowType type) {
        IIProtos.IIResponse.Builder responseBuilder = IIProtos.IIResponse.newBuilder();
        ClearTextDictionary clearTextDictionary = new ClearTextDictionary(recordInfo, type);
        RowKeyColumnIO rowKeyColumnIO = new RowKeyColumnIO(clearTextDictionary);
        final int byteFormLen = recordInfo.getByteFormLen();
        int totalSize = 0;
        byte[] recordBuffer = new byte[byteFormLen];
        int iteratedSliceCount = 0;
        for (Slice slice : slices) {
            iteratedSliceCount++;
            CoprocessorFilter newFilter = CoprocessorFilter.fromFilter(new LocalDictionary(slice.getLocalDictionaries(), type, slice.getInfo()), filter.getFilter(), FilterDecorator.FilterConstantsTreatment.REPLACE_WITH_LOCAL_DICT);
            ConciseSet result = null;
            if (filter != null) {
                result = new BitMapFilterEvaluator(new SliceBitMapProvider(slice, type)).evaluate(newFilter.getFilter());
            }

            Iterator<RawTableRecord> iterator = slice.iterateWithBitmap(result);
            while (iterator.hasNext()) {
                if (totalSize >= MEMORY_LIMIT) {
                    throw new RuntimeException("the query has exceeded the memory limit, please check the query");
                }
                final RawTableRecord rawTableRecord = iterator.next();
                decodeWithDictionary(recordBuffer, rawTableRecord, slice.getLocalDictionaries(), recordInfo, rowKeyColumnIO, type);
                IIProtos.IIResponse.IIRow.Builder rowBuilder = IIProtos.IIResponse.IIRow.newBuilder().setColumns(ByteString.copyFrom(recordBuffer));
                responseBuilder.addRows(rowBuilder.build());
                totalSize += byteFormLen;
            }
        }

        logger.info("Iterated Slices count: " + iteratedSliceCount);

        return responseBuilder.build();
    }

    @Override
    public void start(CoprocessorEnvironment env) throws IOException {
        if (env instanceof RegionCoprocessorEnvironment) {
            this.env = (RegionCoprocessorEnvironment) env;
        } else {
            throw new CoprocessorException("Must be loaded on a table region!");
        }
    }

    @Override
    public void stop(CoprocessorEnvironment env) throws IOException {
    }

    @Override
    public Service getService() {
        return this;
    }
}
