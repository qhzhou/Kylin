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

package org.apache.kylin.engine.mr;

import java.util.List;

import org.apache.kylin.common.util.StringUtil;
import org.apache.kylin.cube.CubeSegment;
import org.apache.kylin.engine.mr.IMROutput.IMRBatchMergeOutputSide;
import org.apache.kylin.engine.mr.common.MapReduceExecutable;
import org.apache.kylin.engine.mr.steps.MergeCuboidJob;
import org.apache.kylin.job.constant.ExecutableConstants;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

public class BatchMergeJobBuilder extends JobBuilderSupport {

    private final IMRBatchMergeOutputSide outputSide;

    public BatchMergeJobBuilder(CubeSegment mergeSegment, String submitter) {
        super(mergeSegment, submitter);
        this.outputSide = MRUtil.getBatchMergeOutputSide(seg);
    }

    public CubingJob build() {
        final CubingJob result = CubingJob.createMergeJob(seg, submitter, config);
        final String jobId = result.getId();
        final String cuboidRootPath = getCuboidRootPath(jobId);

        final List<CubeSegment> mergingSegments = seg.getCubeInstance().getMergingSegments(seg);
        Preconditions.checkState(mergingSegments.size() > 1, "there should be more than 2 segments to merge");
        final List<String> mergingSegmentIds = Lists.newArrayList();
        final List<String> mergingCuboidPaths = Lists.newArrayList();
        for (CubeSegment merging : mergingSegments) {
            mergingSegmentIds.add(merging.getUuid());
            mergingCuboidPaths.add(getCuboidRootPath(merging) + "*");
        }

        // Phase 1: Merge Dictionary
        result.addTask(createMergeDictionaryStep(mergingSegmentIds));

        // Phase 2: Merge Cube Files
        String formattedPath = StringUtil.join(mergingCuboidPaths, ",");
        result.addTask(createMergeCuboidDataStep(seg, formattedPath, cuboidRootPath));
        outputSide.addStepPhase2_BuildCube(result, cuboidRootPath);

        // Phase 3: Update Metadata & Cleanup
        result.addTask(createUpdateCubeInfoAfterMergeStep(mergingSegmentIds, jobId));
        outputSide.addStepPhase3_Cleanup(result);

        return result;
    }

    private MapReduceExecutable createMergeCuboidDataStep(CubeSegment seg, String inputPath, String outputPath) {
        MapReduceExecutable mergeCuboidDataStep = new MapReduceExecutable();
        mergeCuboidDataStep.setName(ExecutableConstants.STEP_NAME_MERGE_CUBOID);
        StringBuilder cmd = new StringBuilder();

        appendMapReduceParameters(cmd, seg);
        appendExecCmdParameters(cmd, "cubename", seg.getCubeInstance().getName());
        appendExecCmdParameters(cmd, "segmentname", seg.getName());
        appendExecCmdParameters(cmd, "input", inputPath);
        appendExecCmdParameters(cmd, "output", outputPath);
        appendExecCmdParameters(cmd, "jobname", "Kylin_Merge_Cuboid_" + seg.getCubeInstance().getName() + "_Step");

        mergeCuboidDataStep.setMapReduceParams(cmd.toString());
        mergeCuboidDataStep.setMapReduceJobClass(MergeCuboidJob.class);
        return mergeCuboidDataStep;
    }

}
