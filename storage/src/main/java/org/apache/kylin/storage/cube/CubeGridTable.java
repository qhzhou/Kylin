package org.apache.kylin.storage.cube;

import java.util.BitSet;
import java.util.List;
import java.util.Map;

import org.apache.kylin.cube.CubeManager;
import org.apache.kylin.cube.CubeSegment;
import org.apache.kylin.cube.cuboid.Cuboid;
import org.apache.kylin.cube.model.CubeDesc;
import org.apache.kylin.dict.Dictionary;
import org.apache.kylin.metadata.model.DataType;
import org.apache.kylin.metadata.model.MeasureDesc;
import org.apache.kylin.metadata.model.TblColRef;
import org.apache.kylin.storage.gridtable.GTInfo;

import com.google.common.collect.Maps;

public class CubeGridTable {

    public static Map<TblColRef, Dictionary<?>> getDimensionToDictionaryMap(CubeSegment cubeSeg, long cuboidId) {
        CubeDesc cubeDesc = cubeSeg.getCubeDesc();
        CubeManager cubeMgr = CubeManager.getInstance(cubeSeg.getCubeInstance().getConfig());

        // build a dictionary map
        Map<TblColRef, Dictionary<?>> dictionaryMap = Maps.newHashMap();
        List<TblColRef> dimCols = Cuboid.findById(cubeDesc, cuboidId).getColumns();
        for (TblColRef col : dimCols) {
            Dictionary<?> dictionary = cubeMgr.getDictionary(cubeSeg, col);
            if (dictionary != null) {
                dictionaryMap.put(col, dictionary);
            }
        }
        return dictionaryMap;
    }

    public static GTInfo newGTInfo(CubeSegment cubeSeg, long cuboidId) {
        Map<TblColRef, Dictionary<?>> dictionaryMap = getDimensionToDictionaryMap(cubeSeg, cuboidId);
        return newGTInfo(cubeSeg.getCubeDesc(), cuboidId, dictionaryMap);
    }

    @SuppressWarnings("rawtypes")
    public static GTInfo newGTInfo(CubeDesc cubeDesc, long cuboidId, Map<TblColRef, Dictionary<?>> dictionaryMap) {
        Cuboid cuboid = Cuboid.findById(cubeDesc, cuboidId);
        List<TblColRef> dimCols = cuboid.getColumns();

        int nColumns = dimCols.size() + cubeDesc.getMeasures().size();
        BitSet dimensions = new BitSet();
        dimensions.set(0, dimCols.size());
        BitSet metrics = new BitSet();
        metrics.set(dimCols.size(), nColumns);
        DataType[] dataTypes = new DataType[nColumns];
        Map<Integer, Dictionary> dictionaryByColIdx = Maps.newHashMap();
        Map<Integer, Integer> fixLenByColIdx = Maps.newHashMap();

        int colIndex = 0;
        for (TblColRef col : dimCols) {
            dataTypes[colIndex] = col.getType();
            if (cubeDesc.getRowkey().isUseDictionary(col)) {
                Dictionary dict = dictionaryMap.get(col);
                if (dict == null)
                    throw new IllegalStateException();

                dictionaryByColIdx.put(colIndex, dict);
            } else {
                int len = cubeDesc.getRowkey().getColumnLength(col);
                if (len == 0)
                    throw new IllegalStateException();
                
                fixLenByColIdx.put(colIndex,  len);
            }
            colIndex++;
        }

        for (MeasureDesc measure : cubeDesc.getMeasures()) {
            dataTypes[colIndex] = measure.getFunction().getReturnDataType();
            colIndex++;
        }

        GTInfo.Builder builder = GTInfo.builder();
        builder.setCodeSystem(new CubeCodeSystem(dictionaryByColIdx, fixLenByColIdx));
        builder.setColumns(dataTypes);
        builder.setPrimaryKey(dimensions);
        builder.enableColumnBlock(new BitSet[] { dimensions, metrics });
        return builder.build();
    }

}
