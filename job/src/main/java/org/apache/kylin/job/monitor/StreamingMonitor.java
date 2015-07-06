package org.apache.kylin.job.monitor;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.methods.ByteArrayRequestEntity;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.lang3.StringUtils;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.util.Bytes;
import org.apache.kylin.common.util.JsonUtil;
import org.apache.kylin.common.util.MailService;
import org.apache.kylin.common.util.Pair;
import org.apache.kylin.cube.CubeInstance;
import org.apache.kylin.cube.CubeManager;
import org.apache.kylin.cube.CubeSegment;
import org.apache.kylin.metadata.model.SegmentStatusEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

/**
 */
public class StreamingMonitor {

    private static final Logger logger = LoggerFactory.getLogger(StreamingMonitor.class);

    public void checkCountAll(List<String> receivers, String host, String authorization, String tableName) {
        String title = "checkCountAll job(host:" + host + " tableName:" + tableName + ") ";
        StringBuilder stringBuilder = new StringBuilder();
        String url = host + "/kylin/api/query";
        PostMethod request = new PostMethod(url);
        try {

            request.addRequestHeader("Authorization", "Basic " + authorization);
            request.addRequestHeader("Content-Type", "application/json");
            String query = String.format("{\"sql\":\"select count(*) from %s\",\"offset\":0,\"limit\":50000,\"acceptPartial\":true,\"project\":\"default\"}", tableName);
            request.setRequestEntity(new ByteArrayRequestEntity(query.getBytes()));

            int statusCode = new HttpClient().executeMethod(request);
            String msg = Bytes.toString(request.getResponseBody());
            stringBuilder.append("host:").append(host).append("\n");
            stringBuilder.append("query:").append(query).append("\n");
            stringBuilder.append("statusCode:").append(statusCode).append("\n");
            if (statusCode == 200) {
                title += "succeed";
                final HashMap hashMap = JsonUtil.readValue(msg, HashMap.class);
                stringBuilder.append("results:").append(hashMap.get("results").toString()).append("\n");
                stringBuilder.append("duration:").append(hashMap.get("duration").toString()).append("\n");
            } else {
                title += "failed";
                stringBuilder.append("response:").append(msg).append("\n");
            }
        } catch (Exception e) {
            final StringWriter out = new StringWriter();
            e.printStackTrace(new PrintWriter(out));
            title += "failed";
            stringBuilder.append(out.toString());
        } finally {
            request.releaseConnection();
        }
        logger.info("title:" + title);
        logger.info("content:" + stringBuilder.toString());
        sendMail(receivers, title, stringBuilder.toString());
    }

    public static final List<Pair<Long, Long>> findGaps(String cubeName) {
        List<CubeSegment> segments = getSortedReadySegments(cubeName);
        List<Pair<Long, Long>> gaps = Lists.newArrayList();
        for (int i = 0; i < segments.size() - 1; ++i) {
            CubeSegment first = segments.get(i);
            CubeSegment second = segments.get(i + 1);
            if (first.getDateRangeEnd() == second.getDateRangeStart()) {
                continue;
            } else if (first.getDateRangeEnd() < second.getDateRangeStart()) {
                gaps.add(Pair.newPair(first.getDateRangeEnd(), second.getDateRangeStart()));
            }
        }
        return gaps;
    }

    private static List<CubeSegment> getSortedReadySegments(String cubeName) {
        final CubeInstance cube = CubeManager.getInstance(KylinConfig.getInstanceFromEnv()).reloadCubeLocal(cubeName);
        Preconditions.checkNotNull(cube);
        final List<CubeSegment> segments = cube.getSegment(SegmentStatusEnum.READY);
        logger.info("totally " + segments.size() + " cubeSegments");
        Collections.sort(segments);
        return segments;
    }

    public static final List<Pair<String, String>> findOverlaps(String cubeName) {
        List<CubeSegment> segments = getSortedReadySegments(cubeName);
        List<Pair<String, String>> overlaps = Lists.newArrayList();
        for (int i = 0; i < segments.size() - 1; ++i) {
            CubeSegment first = segments.get(i);
            CubeSegment second = segments.get(i + 1);
            if (first.getDateRangeEnd() == second.getDateRangeStart()) {
                continue;
            } else {
                overlaps.add(Pair.newPair(first.getName(), second.getName()));
            }
        }
        return overlaps;
    }

    public void checkCube(List<String> receivers, String cubeName) {
        final CubeInstance cube = CubeManager.getInstance(KylinConfig.getInstanceFromEnv()).reloadCubeLocal(cubeName);
        if (cube == null) {
            logger.info("cube:" + cubeName + " does not exist");
            return;
        }
        List<Pair<Long, Long>> gaps = findGaps(cubeName);
        List<Pair<String, String>> overlaps = Lists.newArrayList();
        StringBuilder content = new StringBuilder();
        if (!gaps.isEmpty()) {
            content.append("all gaps:").append("\n").append(
                    StringUtils.join(Lists.transform(gaps, new Function<Pair<Long, Long>, String>() {
                        @Nullable
                        @Override
                        public String apply(Pair<Long, Long> input) {
                            return parseInterval(input);
                        }
                    }), "\n")).append("\n");
        }
        if (!overlaps.isEmpty()) {
            content.append("all overlaps:").append("\n").append(StringUtils.join(overlaps, "\n")).append("\n");
        }
        if (content.length() > 0) {
            logger.info(content.toString());
            sendMail(receivers, String.format("%s has gaps or overlaps", cubeName), content.toString());
        } else {
            logger.info("no gaps or overlaps");
        }
    }

    private String parseInterval(Pair<Long, Long> interval) {
        return String.format("{%d(%s), %d(%s)}", interval.getFirst(), new Date(interval.getFirst()).toString(), interval.getSecond(), new Date(interval.getSecond()).toString());
    }

    private void sendMail(List<String> receivers, String title, String content) {
        final MailService mailService = new MailService(KylinConfig.getInstanceFromEnv());
        mailService.sendMail(receivers, title, content, false);
    }

}
