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

package org.apache.kylin.common.persistence;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HConnection;
import org.apache.hadoop.hbase.client.HConnectionManager;
import org.apache.kylin.common.util.HadoopUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author yangli9
 * 
 */
public class HBaseConnection {

    private static final Logger logger = LoggerFactory.getLogger(HBaseConnection.class);

    private static final Map<String, Configuration> ConfigCache = new ConcurrentHashMap<String, Configuration>();
    private static final Map<String, HConnection> ConnPool = new ConcurrentHashMap<String, HConnection>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                for (HConnection conn : ConnPool.values()) {
                    try {
                        conn.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
    }

    // returned HConnection can be shared by multiple threads and does not require close()
    public static HConnection get(String url) {
        // find configuration
        Configuration conf = ConfigCache.get(url);
        if (conf == null) {
            conf = HadoopUtil.newHBaseConfiguration(url);
            ConfigCache.put(url, conf);
        }

        HConnection connection = ConnPool.get(url);
        try {
            while (true) {
                // I don't use DCL since recreate a connection is not a big issue.
                if (connection == null || connection.isClosed()) {
                    logger.info("connection is null or closed, creating a new one");
                    connection = HConnectionManager.createConnection(conf);
                    ConnPool.put(url, connection);
                }

                if (connection == null || connection.isClosed()) {
                    Thread.sleep(10000);// wait a while and retry
                } else {
                    break;
                }
            }

        } catch (Throwable t) {
            logger.error("Error when open connection " + url, t);
            throw new StorageException("Error when open connection " + url, t);
        }

        return connection;
    }

    public static boolean tableExists(HConnection conn, String tableName) throws IOException {
        HBaseAdmin hbase = new HBaseAdmin(conn);
        try {
            return hbase.tableExists(TableName.valueOf(tableName));
        } finally {
            hbase.close();
        }
    }

    public static boolean tableExists(String hbaseUrl, String tableName) throws IOException {
        return tableExists(HBaseConnection.get(hbaseUrl), tableName);
    }

    public static void createHTableIfNeeded(String hbaseUrl, String tableName, String... families) throws IOException {
        createHTableIfNeeded(HBaseConnection.get(hbaseUrl), tableName, families);
    }

    public static void deleteTable(String hbaseUrl, String tableName) throws IOException {
        deleteTable(HBaseConnection.get(hbaseUrl), tableName);
    }

    public static void createHTableIfNeeded(HConnection conn, String tableName, String... families) throws IOException {
        HBaseAdmin hbase = new HBaseAdmin(conn);

        try {
            if (tableExists(conn, tableName)) {
                logger.debug("HTable '" + tableName + "' already exists");
                return;
            }

            logger.debug("Creating HTable '" + tableName + "'");

            HTableDescriptor desc = new HTableDescriptor(TableName.valueOf(tableName));

            if (null != families && families.length > 0) {
                for (String family : families) {
                    HColumnDescriptor fd = new HColumnDescriptor(family);
                    fd.setInMemory(true); // metadata tables are best in memory
                    desc.addFamily(fd);
                }
            }
            hbase.createTable(desc);

            logger.debug("HTable '" + tableName + "' created");
        } finally {
            hbase.close();
        }
    }

    public static void deleteTable(HConnection conn, String tableName) throws IOException {
        HBaseAdmin hbase = new HBaseAdmin(conn);

        try {
            if (!tableExists(conn, tableName)) {
                logger.debug("HTable '" + tableName + "' does not exists");
                return;
            }

            logger.debug("delete HTable '" + tableName + "'");

            if (hbase.isTableEnabled(tableName)) {
                hbase.disableTable(tableName);
            }
            hbase.deleteTable(tableName);

            logger.debug("HTable '" + tableName + "' deleted");
        } finally {
            hbase.close();
        }
    }

}
