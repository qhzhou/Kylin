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

package org.apache.kylin.rest.service;

import com.google.common.base.Preconditions;
import org.apache.kylin.common.cache.CacheUpdater;
import org.apache.kylin.common.restclient.AbstractRestCache;
import org.apache.kylin.common.restclient.Broadcaster;
import org.apache.kylin.cube.CubeDescManager;
import org.apache.kylin.cube.CubeInstance;
import org.apache.kylin.cube.CubeManager;
import org.apache.kylin.cube.cuboid.Cuboid;
import org.apache.kylin.invertedindex.IIDescManager;
import org.apache.kylin.invertedindex.IIManager;
import org.apache.kylin.metadata.MetadataManager;
import org.apache.kylin.metadata.project.ProjectInstance;
import org.apache.kylin.metadata.project.ProjectManager;
import org.apache.kylin.metadata.realization.RealizationRegistry;
import org.apache.kylin.metadata.realization.RealizationType;
import org.apache.kylin.storage.hybrid.HybridManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;

/**
 */
@Component("cacheService")
public class CacheService extends BasicService {

    @Autowired
    private CacheUpdater cacheUpdater;

    @Autowired
    private CubeService cubeService;

    @PostConstruct
    public void init() throws IOException {
        initCacheUpdater(cacheUpdater);
    }

    public void initCacheUpdater(CacheUpdater cacheUpdater) {
        Preconditions.checkNotNull(cacheUpdater, "cacheManager is not injected yet");
        AbstractRestCache.setCacheUpdater(cacheUpdater);
    }

    public void setCubeService(CubeService cubeService) {
        this.cubeService = cubeService;
    }

    private static final Logger logger = LoggerFactory.getLogger(CacheService.class);

    public void rebuildCache(Broadcaster.TYPE cacheType, String cacheKey) {
        final String log = "rebuild cache type: " + cacheType + " name:" + cacheKey;
        logger.info(log);
        try {
            switch (cacheType) {
                case CUBE:
                    CubeInstance newCube = getCubeManager().reloadCubeLocal(cacheKey);
                    getHybridManager().reloadHybridInstanceByChild(RealizationType.CUBE, cacheKey);
                    getProjectManager().clearL2Cache();
                    //clean query related cache first
                    super.cleanDataCache(newCube.getUuid());
                    cubeService.updateOnNewSegmentReady(cacheKey);
                    break;
                case CUBE_DESC:
                    getCubeDescManager().reloadCubeDescLocal(cacheKey);
                    Cuboid.reloadCache(cacheKey);
                    break;
                case PROJECT:
                    ProjectInstance projectInstance = getProjectManager().reloadProjectLocal(cacheKey);
                    removeOLAPDataSource(projectInstance.getName());
                    break;
                case INVERTED_INDEX:
                    //II update does not need to update storage cache because it is dynamic already
                    getIIManager().reloadIILocal(cacheKey);
                    getHybridManager().reloadHybridInstanceByChild(RealizationType.INVERTED_INDEX, cacheKey);
                    getProjectManager().clearL2Cache();
                    break;
                case INVERTED_INDEX_DESC:
                    getIIDescManager().reloadIIDescLocal(cacheKey);
                    break;
                case TABLE:
                    getMetadataManager().reloadTableCache(cacheKey);
                    IIDescManager.clearCache();
                    CubeDescManager.clearCache();
                    break;
                case DATA_MODEL:
                    getMetadataManager().reloadDataModelDesc(cacheKey);
                    IIDescManager.clearCache();
                    CubeDescManager.clearCache();
                    break;
                case ALL:
                    MetadataManager.clearCache();
                    CubeDescManager.clearCache();
                    CubeManager.clearCache();
                    Cuboid.clearCache();
                    IIDescManager.clearCache();
                    IIManager.clearCache();
                    HybridManager.clearCache();
                    RealizationRegistry.clearCache();
                    ProjectManager.clearCache();
                    super.cleanAllDataCache();
                    BasicService.removeAllOLAPDataSources();
                    break;
                default:
                    throw new RuntimeException("invalid cacheType:" + cacheType);
            }
        } catch (IOException e) {
            throw new RuntimeException("error " + log, e);
        }
    }

    public void removeCache(Broadcaster.TYPE cacheType, String cacheKey) {
        final String log = "remove cache type: " + cacheType + " name:" + cacheKey;
        try {
            switch (cacheType) {
                case CUBE:
                    String storageUUID = getCubeManager().getCube(cacheKey).getUuid();
                    getCubeManager().removeCubeLocal(cacheKey);
                    super.cleanDataCache(storageUUID);
                    break;
                case CUBE_DESC:
                    getCubeDescManager().removeLocalCubeDesc(cacheKey);
                    Cuboid.reloadCache(cacheKey);
                    break;
                case PROJECT:
                    ProjectManager.clearCache();
                    break;
                case INVERTED_INDEX:
                    getIIManager().removeIILocal(cacheKey);
                    break;
                case INVERTED_INDEX_DESC:
                    getIIDescManager().removeIIDescLocal(cacheKey);
                    break;
                case TABLE:
                    throw new UnsupportedOperationException(log);
                case DATA_MODEL:
                    throw new UnsupportedOperationException(log);
                default:
                    throw new RuntimeException("invalid cacheType:" + cacheType);
            }
        } catch (IOException e) {
            throw new RuntimeException("error " + log, e);
        }
    }
}

