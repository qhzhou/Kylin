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
package com.kylinolap.rest.service;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.kylinolap.common.KylinConfig;
import com.kylinolap.cube.CubeDescManager;
import com.kylinolap.cube.CubeManager;
import com.kylinolap.invertedindex.IIDescManager;
import com.kylinolap.job.cube.CubingJob;
import com.kylinolap.job.execution.ExecutableState;
import com.kylinolap.job.impl.threadpool.AbstractExecutable;
import com.kylinolap.job.service.ExecutableManager;
import com.kylinolap.metadata.MetadataManager;
import com.kylinolap.metadata.project.ProjectInstance;
import com.kylinolap.metadata.project.ProjectManager;
import com.kylinolap.metadata.realization.RealizationType;
import com.kylinolap.query.enumerator.OLAPQuery;
import com.kylinolap.query.relnode.OLAPContext;
import com.kylinolap.query.schema.OLAPSchemaFactory;
import com.kylinolap.rest.controller.QueryController;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public abstract class BasicService {

    private static final Logger logger = LoggerFactory.getLogger(BasicService.class);

    private static ConcurrentMap<String, DataSource> olapDataSources = new ConcurrentHashMap<String, DataSource>();

    //    @Autowired
    //    protected JdbcTemplate jdbcTemplate;

    public KylinConfig getConfig() {
        return KylinConfig.getInstanceFromEnv();
    }

    public void removeOLAPDataSource(String project) {
        if (StringUtils.isEmpty(project))
            throw new IllegalArgumentException("removeOLAPDataSource: project name not given");

        project = ProjectInstance.getNormalizedProjectName(project);
        olapDataSources.remove(project);
    }

    public static void resetOLAPDataSources() {
        // brutal, yet simplest way
        logger.info("resetOLAPDataSources is called.");
        olapDataSources = new ConcurrentHashMap<String, DataSource>();
    }

    public DataSource getOLAPDataSource(String project) {

        project = ProjectInstance.getNormalizedProjectName(project);

        DataSource ret = olapDataSources.get(project);
        if (ret == null) {
            logger.debug("Creating a new data source");
            logger.debug("OLAP data source pointing to " + getConfig());

            File modelJson = OLAPSchemaFactory.createTempOLAPJson(project, getConfig());

            try {
                List<String> text = Files.readLines(modelJson, Charset.defaultCharset());
                logger.debug("The new temp olap json is :");
                for (String line : text)
                    logger.debug(line);
            } catch (IOException e) {
                e.printStackTrace(); // logging failure is not critical
            }

            DriverManagerDataSource ds = new DriverManagerDataSource();
            Properties props = new Properties();
            props.setProperty(OLAPQuery.PROP_SCAN_THRESHOLD, String.valueOf(KylinConfig.getInstanceFromEnv().getScanThreshold()));
            ds.setConnectionProperties(props);
            ds.setDriverClassName("net.hydromatic.optiq.jdbc.Driver");
            ds.setUrl("jdbc:calcite:model=" + modelJson.getAbsolutePath());

            ret = olapDataSources.putIfAbsent(project, ds);
            if (ret == null) {
                ret = ds;
            }
        }
        return ret;
    }

    /**
     * Reload changed cube into cache
     * 
     * @throws IOException
     */
    @Caching(evict = { @CacheEvict(value = QueryController.SUCCESS_QUERY_CACHE, allEntries = true), @CacheEvict(value = QueryController.EXCEPTION_QUERY_CACHE, allEntries = true) })
    public void cleanDataCache() {
        CubeManager.removeInstance(getConfig());
        ProjectManager.removeInstance(getConfig());
        BasicService.resetOLAPDataSources();
    }

    public final KylinConfig getKylinConfig() {
        KylinConfig kylinConfig = KylinConfig.getInstanceFromEnv();

        if (kylinConfig == null) {
            throw new IllegalArgumentException("Failed to load kylin config instance");
        }

        return kylinConfig;
    }

    public final MetadataManager getMetadataManager() {
        return MetadataManager.getInstance(getConfig());
    }

    public final CubeManager getCubeManager() {
        return CubeManager.getInstance(getConfig());
    }

    public final CubeDescManager getCubeDescManager() {
        return CubeDescManager.getInstance(getConfig());
    }

    public final ProjectManager getProjectManager() {
        return ProjectManager.getInstance(getConfig());
    }

    public final ExecutableManager getExecutableManager() {
        return ExecutableManager.getInstance(getConfig());
    }

    public final IIDescManager getIIDescManager() {
        return IIDescManager.getInstance(getConfig());
    }

    protected List<CubingJob> listAllCubingJobs(final String cubeName, final String projectName, final Set<ExecutableState> statusList) {
        List<CubingJob> results = Lists.newArrayList(FluentIterable.from(getExecutableManager().getAllExecutables()).filter(new Predicate<AbstractExecutable>() {
            @Override
            public boolean apply(AbstractExecutable executable) {
                if (executable instanceof CubingJob) {
                    if (cubeName == null) {
                        return true;
                    }
                    return ((CubingJob) executable).getCubeName().equalsIgnoreCase(cubeName);                    
                } else {
                    return false;
                }
            }
        }).transform(new Function<AbstractExecutable, CubingJob>() {
            @Override
            public CubingJob apply(AbstractExecutable executable) {
                return (CubingJob) executable;
            }
        }).filter(new Predicate<CubingJob>() {
            @Override
            public boolean apply(CubingJob executable) {
                if (null == projectName || null == getProjectManager().getProject(projectName)) {
                    return true;
                } else {
                    ProjectInstance project = getProjectManager().getProject(projectName);
                    return project.containsRealization(RealizationType.CUBE, executable.getCubeName());
                }
            }
        }).filter(new Predicate<CubingJob>() {
            @Override
            public boolean apply(CubingJob executable) {
                return statusList.contains(executable.getStatus());
            }
        }));
        return results;
    }

    protected List<CubingJob> listAllCubingJobs(final String cubeName, final String projectName) {
        return listAllCubingJobs(cubeName, projectName, EnumSet.allOf(ExecutableState.class));
    }

    protected static void close(ResultSet resultSet, Statement stat, Connection conn) {
        OLAPContext.clearParameter();

        if (resultSet != null)
            try {
                resultSet.close();
            } catch (SQLException e) {
                logger.error("failed to close", e);
            }
        if (stat != null)
            try {
                stat.close();
            } catch (SQLException e) {
                logger.error("failed to close", e);
            }
        if (conn != null)
            try {
                conn.close();
            } catch (SQLException e) {
                logger.error("failed to close", e);
            }
    }

}
