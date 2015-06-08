package org.apache.kylin.streaming;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;

/**
 */
public class StreamFetcher implements Callable<MicroStreamBatch> {
    private static final Logger logger = LoggerFactory.getLogger(StreamBuilder.class);

    private final BlockingQueue<StreamMessage> streamMessageQueue;
    private final CountDownLatch countDownLatch;
    private final int partitionId;
    private final BatchCondition condition;
    private final StreamParser streamParser;

    public StreamFetcher(int partitionId, BlockingQueue<StreamMessage> streamMessageQueue, CountDownLatch countDownLatch, BatchCondition condition, StreamParser streamParser) {
        this.partitionId = partitionId;
        this.streamMessageQueue = streamMessageQueue;
        this.countDownLatch = countDownLatch;
        this.condition = condition;
        this.streamParser = streamParser;
    }

    private void clearCounter() {
    }

    private StreamMessage peek(BlockingQueue<StreamMessage> queue, long timeout) {
        long t = System.currentTimeMillis();
        while (true) {
            final StreamMessage peek = queue.peek();
            if (peek != null) {
                return peek;
            } else {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    logger.warn("stream queue should not be interrupted", e);
                    return null;
                }
                if (System.currentTimeMillis() - t > timeout) {
                    break;
                }
            }
        }
        return queue.peek();
    }

    @Override
    public MicroStreamBatch call() throws Exception {
        try {
            MicroStreamBatch microStreamBatch = null;
            while (true) {
                if (microStreamBatch == null) {
                    microStreamBatch = new MicroStreamBatch(partitionId);
                    clearCounter();
                }
                StreamMessage streamMessage = peek(streamMessageQueue, 30000);
                if (streamMessage == null) {
                    logger.info("The stream queue is drained, current available stream count: " + microStreamBatch.size());
                    if (!microStreamBatch.isEmpty()) {
                        return microStreamBatch;
                    } else {
                        continue;
                    }
                }
                if (streamMessage.getOffset() < 0) {
                    logger.warn("streaming encountered EOF, stop building");
                    return null;
                }

                microStreamBatch.incRawMessageCount();
                final ParsedStreamMessage parsedStreamMessage = streamParser.parse(streamMessage);
                if (parsedStreamMessage == null) {
                    throw new RuntimeException("parsedStreamMessage of " + new String(streamMessage.getRawData()) + " is null");
                }

                final BatchCondition.Result result = condition.apply(parsedStreamMessage);
                if (parsedStreamMessage.isAccepted()) {
                    if (result == BatchCondition.Result.ACCEPT) {
                        streamMessageQueue.take();
                        microStreamBatch.add(parsedStreamMessage);
                    } else if (result == BatchCondition.Result.DISCARD) {
                        streamMessageQueue.take();
                    } else if (result == BatchCondition.Result.REJECT) {
                        return microStreamBatch;
                    }
                } else {
                    streamMessageQueue.take();
                }
            }
        } catch (Exception e) {
            logger.error("build stream error, stop building", e);
            throw new RuntimeException("build stream error, stop building", e);
        } finally {
            logger.info("one partition sign off");
            countDownLatch.countDown();
        }
    }
}
