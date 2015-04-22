package org.apache.kylin.storage.cache;

import java.util.Iterator;
import java.util.List;
import java.util.NavigableMap;

import javax.annotation.Nullable;

import com.google.common.base.Preconditions;
import org.apache.kylin.common.util.RangeUtil;
import org.apache.kylin.metadata.model.TblColRef;
import org.apache.kylin.metadata.tuple.ITuple;
import org.apache.kylin.storage.tuple.Tuple;

import com.google.common.base.Function;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Range;

/**
 * Created by Hongbin Ma(Binmahone) on 4/13/15.
 */

public class StreamSQLResult {
    private Range<Long> timeCovered;
    private NavigableMap<Long, List<ITuple>> sortedRows;

    public StreamSQLResult(List<ITuple> rows, Range<Long> timeCovered, TblColRef partitionCol) {

        Preconditions.checkArgument(timeCovered.hasUpperBound(),"StreamSQLResult requires timeCovered having a upperBound");

        sortedRows = Maps.newTreeMap();
        for (ITuple row : rows) {

            long t = Tuple.getTs(row,partitionCol);

            //will only cache rows that are within the time range
            if (timeCovered.contains(t)) {
                if (!this.sortedRows.containsKey(t)) {
                    this.sortedRows.put(t, Lists.newArrayList(row));
                } else {
                    this.sortedRows.get(t).add(row);
                }
            }
        }
        this.timeCovered = timeCovered;
    }

    public Range<Long> getReusableResults(Range<Long> tsRange) {

        if (tsRange.equals(timeCovered))
            return timeCovered;

        if (!timeCovered.isConnected(tsRange)) {
            //share nothing in common
            return null;
        }

        Range<Long> ret = timeCovered.intersection(tsRange);
        return ret.isEmpty() ? null : ret;
    }

    public Iterator<ITuple> reuse(Range<Long> reusablePeriod) {
        NavigableMap<Long, List<ITuple>> submap = RangeUtil.filter(sortedRows, reusablePeriod);
        return Iterators.concat(Iterators.transform(submap.values().iterator(), new Function<List<ITuple>, Iterator<ITuple>>() {
            @Nullable
            @Override
            public Iterator<ITuple> apply(List<ITuple> input) {
                return input.iterator();
            }
        }));
    }

    @Override
    public String toString() {
        return sortedRows.size() + " tuples cached for period " + RangeUtil.formatTsRange(timeCovered);
    }

    public long getEndTime()
    {
        return this.timeCovered.upperEndpoint();
    }
}
