package org.apache.kylin.job.streaming;

/**
 */
public class BootstrapConfig {

    private String streaming;
    private int partitionId = -1;

    //one off default value set to true
    private boolean oneOff = true;
    private long start = 0L;
    private long end = 0L;

    private boolean fillGap;


    public boolean isOneOff() {
        return oneOff;
    }

    public void setOneOff(boolean oneOff) {
        this.oneOff = oneOff;
    }

    public long getStart() {
        return start;
    }

    public void setStart(long start) {
        this.start = start;
    }

    public long getEnd() {
        return end;
    }

    public void setEnd(long end) {
        this.end = end;
    }

    public String getStreaming() {
        return streaming;
    }

    public void setStreaming(String streaming) {
        this.streaming = streaming;
    }

    public int getPartitionId() {
        return partitionId;
    }

    public void setPartitionId(int partitionId) {
        this.partitionId = partitionId;
    }

    public boolean isFillGap() {
        return fillGap;
    }

    public void setFillGap(boolean fillGap) {
        this.fillGap = fillGap;
    }
}
