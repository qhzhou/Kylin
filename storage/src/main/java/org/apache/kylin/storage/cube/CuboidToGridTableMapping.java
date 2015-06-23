package org.apache.kylin.storage.cube;

import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.kylin.common.util.ImmutableBitSet;
import org.apache.kylin.cube.cuboid.Cuboid;
import org.apache.kylin.cube.model.CubeDesc;
import org.apache.kylin.cube.model.HBaseColumnDesc;
import org.apache.kylin.cube.model.HBaseColumnFamilyDesc;
import org.apache.kylin.metadata.model.DataType;
import org.apache.kylin.metadata.model.FunctionDesc;
import org.apache.kylin.metadata.model.MeasureDesc;
import org.apache.kylin.metadata.model.TblColRef;

import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

public class CuboidToGridTableMapping {

    final private Cuboid cuboid;

    private List<DataType> gtDataTypes;
    private List<ImmutableBitSet> gtColBlocks;

    private int nDimensions;
    private Map<TblColRef, Integer> dim2gt;
    private ImmutableBitSet gtPrimaryKey;

    private int nMetrics;
    private ListMultimap<FunctionDesc, Integer> metrics2gt; // because count distinct may have a holistic version

    public CuboidToGridTableMapping(Cuboid cuboid) {
        this.cuboid = cuboid;
        init();
    }
    
    private void init() {
        int gtColIdx = 0;
        gtDataTypes = Lists.newArrayList();
        gtColBlocks = Lists.newArrayList();

        // dimensions
        dim2gt = Maps.newHashMap();
        BitSet pk = new BitSet();
        for (TblColRef dimension : cuboid.getColumns()) {
            gtDataTypes.add(dimension.getType());
            dim2gt.put(dimension, gtColIdx);
            pk.set(gtColIdx);
            gtColIdx++;
        }
        gtPrimaryKey = new ImmutableBitSet(pk);
        gtColBlocks.add(gtPrimaryKey);

        nDimensions = gtColIdx;
        assert nDimensions == cuboid.getColumns().size();

        // metrics
        metrics2gt = LinkedListMultimap.create();
        for (HBaseColumnFamilyDesc familyDesc : cuboid.getCube().getHbaseMapping().getColumnFamily()) {
            for (HBaseColumnDesc hbaseColDesc : familyDesc.getColumns()) {
                BitSet colBlock = new BitSet();
                for (MeasureDesc measure : hbaseColDesc.getMeasures()) {
                    // Count distinct & holistic count distinct are equals() but different.
                    // Ensure the holistic version if exists is always the first.
                    FunctionDesc func = measure.getFunction();
                    if (func.isHolisticCountDistinct()) {
                        List<Integer> existing = metrics2gt.removeAll(func);
                        metrics2gt.put(func, gtColIdx);
                        metrics2gt.putAll(func, existing);
                    } else {
                        metrics2gt.put(func, gtColIdx);
                    }
                    gtDataTypes.add(func.getReturnDataType());
                    colBlock.set(gtColIdx);
                    gtColIdx++;
                }
                gtColBlocks.add(new ImmutableBitSet(colBlock));
            }
        }
        nMetrics = gtColIdx - nDimensions;
        assert nMetrics == cuboid.getCube().getMeasures().size();
    }

    public int getColumnCount() {
        return nDimensions + nMetrics;
    }

    public int getDimensionCount() {
        return nDimensions;
    }

    public int getMetricsCount() {
        return nMetrics;
    }

    public DataType[] getDataTypes() {
        return (DataType[]) gtDataTypes.toArray(new DataType[gtDataTypes.size()]);
    }

    public ImmutableBitSet getPrimaryKey() {
        return gtPrimaryKey;
    }

    public ImmutableBitSet[] getColumnBlocks() {
        return (ImmutableBitSet[]) gtColBlocks.toArray(new ImmutableBitSet[gtColBlocks.size()]);
    }

    public int getIndexOf(TblColRef dimension) {
        Integer i = dim2gt.get(dimension);
        return i == null ? -1 : i.intValue();
    }

    public int getIndexOf(FunctionDesc metric) {
        List<Integer> list = metrics2gt.get(metric);
        // normal case
        if (list.size() == 1) {
            return list.get(0);
        }
        // count distinct & its holistic version
        else if (list.size() == 2) {
            assert metric.isCountDistinct();
            return metric.isHolisticCountDistinct() ? list.get(0) : list.get(1);
        }
        // unexpected
        else
            return -1;
    }
    
    public List<TblColRef> getCuboidDimensionsInGTOrder() {
        return cuboid.getColumns();
    }
    
    public Map<Integer, Integer> getDependentMetricsMap() {
        Map<Integer, Integer> result = Maps.newHashMap();
        List<MeasureDesc> measures = cuboid.getCube().getMeasures();
        for (MeasureDesc child : measures) {
            if (child.getDependentMeasureRef() != null) {
                boolean ok = false;
                for (MeasureDesc parent : measures) {
                    if (parent.getName().equals(child.getDependentMeasureRef())) {
                        int childIndex = getIndexOf(child.getFunction());
                        int parentIndex = getIndexOf(parent.getFunction());
                        result.put(childIndex, parentIndex);
                        ok = true;
                        break;
                    }
                }
                if (!ok)
                    throw new IllegalStateException("Cannot find dependent measure: " + child.getDependentMeasureRef());
            }
        }
        return result.isEmpty() ? Collections.<Integer, Integer>emptyMap() : result;
    }

    public static MeasureDesc[] getMeasureSequenceOnGridTable(CubeDesc cube) {
        MeasureDesc[] result = new MeasureDesc[cube.getMeasures().size()];
        int i = 0;
        for (HBaseColumnFamilyDesc familyDesc : cube.getHbaseMapping().getColumnFamily()) {
            for (HBaseColumnDesc hbaseColDesc : familyDesc.getColumns()) {
                for (MeasureDesc m : hbaseColDesc.getMeasures()) {
                    result[i++] = m;
                }
            }
        }
        return result;
    }
}
