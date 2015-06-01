package org.apache.kylin.storage.gridtable;

import java.io.IOException;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.apache.kylin.common.util.ImmutableBitSet;
import org.apache.kylin.storage.gridtable.IGTStore.IGTStoreScanner;

public class GTRawScanner implements IGTScanner {

    final GTInfo info;
    final IGTStoreScanner storeScanner;
    final ImmutableBitSet selectedColBlocks;

    private GTRowBlock.Reader curBlockReader;
    private GTRecord next;
    final private GTRecord oneRecord; // avoid instance creation

    private int scannedRowCount = 0;
    private int scannedRowBlockCount = 0;

    public GTRawScanner(GTInfo info, IGTStore store, GTScanRequest req) throws IOException {
        this.info = info;
        this.selectedColBlocks = info.selectColumnBlocks(req.getColumns());
        this.storeScanner = store.scan(req.getPkStart(), req.getPkEnd(), selectedColBlocks, req);
        this.oneRecord = new GTRecord(info);
    }

    @Override
    public GTInfo getInfo() {
        return info;
    }

    @Override
    public int getScannedRowCount() {
        return scannedRowCount;
    }

    @Override
    public int getScannedRowBlockCount() {
        return scannedRowBlockCount;
    }

    @Override
    public void close() throws IOException {
        storeScanner.close();
    }

    @Override
    public Iterator<GTRecord> iterator() {
        return new Iterator<GTRecord>() {

            @Override
            public boolean hasNext() {
                if (next != null)
                    return true;

                if (fetchOneRecord()) {
                    next = oneRecord;
                    return true;
                } else {
                    return false;
                }
            }

            private boolean fetchOneRecord() {
                while (true) {
                    // get a block
                    if (curBlockReader == null) {
                        if (storeScanner.hasNext()) {
                            curBlockReader = storeScanner.next().getReader(selectedColBlocks);
                            scannedRowBlockCount++;
                        } else {
                            return false;
                        }
                    }
                    // if block exhausted, try next block
                    if (curBlockReader.hasNext() == false) {
                        curBlockReader = null;
                        continue;
                    }
                    // fetch a row
                    curBlockReader.fetchNext(oneRecord);
                    scannedRowCount++;
                    return true;
                }
            }

            @Override
            public GTRecord next() {
                // fetch next record
                if (next == null) {
                    hasNext();
                    if (next == null)
                        throw new NoSuchElementException();
                }

                GTRecord result = next;
                next = null;
                return result;
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

}
