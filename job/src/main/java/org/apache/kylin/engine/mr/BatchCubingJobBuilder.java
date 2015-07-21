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

import org.apache.kylin.cube.CubeSegment;
import org.apache.kylin.engine.mr.IMRInput.IMRBatchCubingInputSide;
import org.apache.kylin.engine.mr.IMROutput.IMRBatchCubingOutputSide;
import org.apache.kylin.job.common.MapReduceExecutable;
import org.apache.kylin.job.constant.ExecutableConstants;
import org.apache.kylin.job.hadoop.cube.BaseCuboidJob;
import org.apache.kylin.job.hadoop.cube.NDCuboidJob;

public class BatchCubingJobBuilder extends JobBuilderSupport {

    private final IMRBatchCubingInputSide inputSide;
    private final IMRBatchCubingOutputSide outputSide;

    public BatchCubingJobBuilder(CubeSegment newSegment, String submitter) {
        super(newSegment, submitter);
        this.inputSide = MRUtil.getBatchCubingInputSide(seg);
        this.outputSide = MRUtil.getBatchCubingOutputSide(seg);
    }

    public CubingJob build() {
        final CubingJob result = CubingJob.createBuildJob(seg, submitter, config);
        final String jobId = result.getId();
        final String cuboidRootPath = getCuboidRootPath(jobId);

        // Phase 1: Create Flat Table
        inputSide.addStepPhase1_CreateFlatTable(result);

        // Phase 2: Build Dictionary
        result.addTask(createFactDistinctColumnsStep(jobId));
        result.addTask(createBuildDictionaryStep(jobId));

        // Phase 3: Build Cube
        final int groupRowkeyColumnsCount = seg.getCubeDesc().getRowkey().getNCuboidBuildLevels();
        final int totalRowkeyColumnsCount = seg.getCubeDesc().getRowkey().getRowKeyColumns().length;
        final String[] cuboidOutputTempPath = getCuboidOutputPaths(cuboidRootPath, totalRowkeyColumnsCount, groupRowkeyColumnsCount);
        // base cuboid step
        result.addTask(createBaseCuboidStep(cuboidOutputTempPath, jobId));
        // n dim cuboid steps
        for (int i = 1; i <= groupRowkeyColumnsCount; i++) {
            int dimNum = totalRowkeyColumnsCount - i;
            result.addTask(createNDimensionCuboidStep(cuboidOutputTempPath, dimNum, totalRowkeyColumnsCount));
        }
        outputSide.addStepPhase3_BuildCube(result, cuboidRootPath);

        // Phase 4: Update Metadata & Cleanup
        result.addTask(createUpdateCubeInfoAfterBuildStep(jobId));
        inputSide.addStepPhase4_Cleanup(result);
        outputSide.addStepPhase4_Cleanup(result);

        return result;
    }

    private MapReduceExecutable createBaseCuboidStep(String[] cuboidOutputTempPath, String jobId) {
        // base cuboid job
        MapReduceExecutable baseCuboidStep = new MapReduceExecutable();

        StringBuilder cmd = new StringBuilder();
        appendMapReduceParameters(cmd, seg);

        baseCuboidStep.setName(ExecutableConstants.STEP_NAME_BUILD_BASE_CUBOID);

        appendExecCmdParameters(cmd, "cubename", seg.getCubeInstance().getName());
        appendExecCmdParameters(cmd, "segmentname", seg.getName());
        appendExecCmdParameters(cmd, "input", ""); // marks flat table input
        appendExecCmdParameters(cmd, "output", cuboidOutputTempPath[0]);
        appendExecCmdParameters(cmd, "jobname", "Kylin_Base_Cuboid_Builder_" + seg.getCubeInstance().getName());
        appendExecCmdParameters(cmd, "level", "0");

        baseCuboidStep.setMapReduceParams(cmd.toString());
        baseCuboidStep.setMapReduceJobClass(BaseCuboidJob.class);
        baseCuboidStep.setCounterSaveAs(CubingJob.SOURCE_RECORD_COUNT + "," + CubingJob.SOURCE_SIZE_BYTES);
        return baseCuboidStep;
    }

    private MapReduceExecutable createNDimensionCuboidStep(String[] cuboidOutputTempPath, int dimNum, int totalRowkeyColumnCount) {
        // ND cuboid job
        MapReduceExecutable ndCuboidStep = new MapReduceExecutable();

        ndCuboidStep.setName(ExecutableConstants.STEP_NAME_BUILD_N_D_CUBOID + " : " + dimNum + "-Dimension");
        StringBuilder cmd = new StringBuilder();

        appendMapReduceParameters(cmd, seg);
        appendExecCmdParameters(cmd, "cubename", seg.getCubeInstance().getName());
        appendExecCmdParameters(cmd, "segmentname", seg.getName());
        appendExecCmdParameters(cmd, "input", cuboidOutputTempPath[totalRowkeyColumnCount - dimNum - 1]);
        appendExecCmdParameters(cmd, "output", cuboidOutputTempPath[totalRowkeyColumnCount - dimNum]);
        appendExecCmdParameters(cmd, "jobname", "Kylin_ND-Cuboid_Builder_" + seg.getCubeInstance().getName() + "_Step");
        appendExecCmdParameters(cmd, "level", "" + (totalRowkeyColumnCount - dimNum));

        ndCuboidStep.setMapReduceParams(cmd.toString());
        ndCuboidStep.setMapReduceJobClass(NDCuboidJob.class);
        return ndCuboidStep;
    }

    private String[] getCuboidOutputPaths(String cuboidRootPath, int totalRowkeyColumnCount, int groupRowkeyColumnsCount) {
        String[] paths = new String[groupRowkeyColumnsCount + 1];
        for (int i = 0; i <= groupRowkeyColumnsCount; i++) {
            int dimNum = totalRowkeyColumnCount - i;
            if (dimNum == totalRowkeyColumnCount) {
                paths[i] = cuboidRootPath + "base_cuboid";
            } else {
                paths[i] = cuboidRootPath + dimNum + "d_cuboid";
            }
        }
        return paths;
    }

}
