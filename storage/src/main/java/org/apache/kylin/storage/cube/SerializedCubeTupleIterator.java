package org.apache.kylin.storage.cube;

import java.util.List;

import com.google.common.collect.Range;
import org.apache.kylin.metadata.tuple.ITuple;
import org.apache.kylin.metadata.tuple.ITupleIterator;

public class SerializedCubeTupleIterator implements ITupleIterator {

    public SerializedCubeTupleIterator(List<CubeScanner> scanners) {
        // TODO Auto-generated constructor stub
    }

    @Override
    public boolean hasNext() {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public ITuple next() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
        // TODO Auto-generated method stub

    }

    @Override
    public Range<Long> getCacheExcludedPeriod() {
        return null;
    }

}
