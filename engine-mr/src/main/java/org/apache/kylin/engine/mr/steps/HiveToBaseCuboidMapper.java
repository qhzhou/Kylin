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

package org.apache.kylin.engine.mr.steps;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import org.apache.kylin.engine.mr.IMRInput.IMRTableInputFormat;
import org.apache.kylin.engine.mr.common.BatchConstants;
import org.apache.kylin.engine.mr.MRUtil;

/**
 * @author George Song (ysong1)
 */
public class HiveToBaseCuboidMapper<KEYIN> extends BaseCuboidMapperBase<KEYIN, Object> {
    
    private IMRTableInputFormat flatTableInputFormat;

    @Override
    protected void setup(Context context) throws IOException {
        super.setup(context);
        flatTableInputFormat = MRUtil.getBatchCubingInputSide(cubeSegment).getFlatTableInputFormat();
    }

    @Override
    public void map(KEYIN key, Object value, Context context) throws IOException, InterruptedException {
        counter++;
        if (counter % BatchConstants.COUNTER_MAX == 0) {
            logger.info("Handled " + counter + " records!");
        }
        

        try {
            //put a record into the shared bytesSplitter
            String[] row = flatTableInputFormat.parseMapperInput(value);
            bytesSplitter.setBuffers(convertUTF8Bytes(row));
            //take care of the data in bytesSplitter
            outputKV(context);

        } catch (Exception ex) {
            handleErrorRecord(bytesSplitter, ex);
        }
    }

    private byte[][] convertUTF8Bytes(String[] row) throws UnsupportedEncodingException {
        byte[][] result = new byte[row.length][];
        for (int i = 0; i < row.length; i++) {
            result[i] = row[i].getBytes("UTF-8");
        }
        return result;
    }

}
