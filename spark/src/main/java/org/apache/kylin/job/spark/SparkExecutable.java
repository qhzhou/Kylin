package org.apache.kylin.job.spark;

import com.google.common.base.Preconditions;
import org.apache.commons.lang3.StringUtils;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.util.Logger;
import org.apache.kylin.job.exception.ExecuteException;
import org.apache.kylin.job.execution.AbstractExecutable;
import org.apache.kylin.job.execution.ExecutableContext;
import org.apache.kylin.job.execution.ExecuteResult;

import java.io.IOException;
import java.util.Map;

/**
 */
public class SparkExecutable extends AbstractExecutable {

    private static final String CLASS_NAME = "className";

    public void setClassName(String className) {
        this.setParam(CLASS_NAME, className);
    }

    private String formatArgs() {
        StringBuilder stringBuilder = new StringBuilder();
        for (Map.Entry<String, String> entry : getParams().entrySet()) {
            stringBuilder.append("-").append(entry.getKey()).append(" ").append(entry.getValue()).append(" ");
        }
        if (stringBuilder.length() > 0) {
            return stringBuilder.substring(0, stringBuilder.length() - 1).toString();
        } else {
            return StringUtils.EMPTY;
        }
    }

    @Override
    protected ExecuteResult doWork(ExecutableContext context) throws ExecuteException {
        final KylinConfig config = context.getConfig();
        Preconditions.checkNotNull(config.getSparkHome());
        Preconditions.checkNotNull(config.getSparkMaster());
        try {
            String cmd = String.format("%s/bin/spark-submit --class \"org.apache.kylin.job.spark.SparkEntry\" --master %s %s %s",
                    config.getSparkHome(),
                    config.getSparkMaster(),
                    config.getKylinJobJarPath(),
                    formatArgs());
            logger.info("cmd:" + cmd);
            final StringBuilder output = new StringBuilder();
            config.getCliCommandExecutor().execute(cmd, new Logger() {
                @Override
                public void log(String message) {
                    output.append(message);
                    output.append("\n");
                }
            });
            return new ExecuteResult(ExecuteResult.State.SUCCEED, output.toString());
        } catch (IOException e) {
            logger.error("error run spark job:", e);
            return new ExecuteResult(ExecuteResult.State.ERROR, e.getLocalizedMessage());
        }
    }
}
