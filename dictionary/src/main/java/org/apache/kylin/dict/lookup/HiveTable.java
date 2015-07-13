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

package org.apache.kylin.dict.lookup;

import java.io.IOException;

import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.util.HiveClient;
import org.apache.kylin.common.util.Pair;
import org.apache.kylin.metadata.MetadataManager;
import org.apache.kylin.metadata.model.TableDesc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 */
public class HiveTable implements ReadableTable {

    private static final Logger logger = LoggerFactory.getLogger(HiveTable.class);

    final private String database;
    final private String hiveTable;
    
    private HiveClient hiveClient;

    public HiveTable(MetadataManager metaMgr, String table) {
        TableDesc tableDesc = metaMgr.getTableDesc(table);
        this.database = tableDesc.getDatabase();
        this.hiveTable = tableDesc.getName();
    }

    @Override
    public TableReader getReader() throws IOException {
        return new HiveTableReader(database, hiveTable);
    }

    @Override
    public TableSignature getSignature() throws IOException {
        try {
            String path = computeHDFSLocation();
            Pair<Long, Long> sizeAndLastModified = FileTable.getSizeAndLastModified(path);
            long size = sizeAndLastModified.getFirst();
            long lastModified = sizeAndLastModified.getSecond();

            // for non-native hive table, cannot rely on size & last modified on HDFS
            if (getHiveClient().isNativeTable(database, hiveTable) == false) {
                lastModified = System.currentTimeMillis(); // assume table is ever changing
            }

            return new TableSignature(path, size, lastModified);

        } catch (Exception e) {
            if (e instanceof IOException)
                throw (IOException) e;
            else
                throw new IOException(e);
        }
    }

    private String computeHDFSLocation() throws Exception {

        String override = KylinConfig.getInstanceFromEnv().getOverrideHiveTableLocation(hiveTable);
        if (override != null) {
            logger.debug("Override hive table location " + hiveTable + " -- " + override);
            return override;
        }

        return getHiveClient().getHiveTableLocation(database, hiveTable);
    }

    public HiveClient getHiveClient() {

        if (hiveClient == null) {
            hiveClient = new HiveClient();
        }
        return hiveClient;
    }

    @Override
    public String toString() {
        return "hive: database=[" + database + "], table=[" + hiveTable + "]";
    }

}
