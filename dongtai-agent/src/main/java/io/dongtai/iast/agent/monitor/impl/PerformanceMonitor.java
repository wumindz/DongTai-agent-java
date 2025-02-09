package io.dongtai.iast.agent.monitor.impl;

import io.dongtai.iast.agent.IastProperties;
import io.dongtai.iast.agent.manager.EngineManager;
import io.dongtai.iast.agent.monitor.IMonitor;
import io.dongtai.iast.agent.monitor.MonitorDaemonThread;
import io.dongtai.iast.agent.monitor.collector.IPerformanceCollector;
import io.dongtai.iast.agent.monitor.collector.MetricsBindCollectorEnum;
import io.dongtai.iast.agent.util.ThreadUtils;
import io.dongtai.iast.common.constants.AgentConstant;
import io.dongtai.iast.common.entity.performance.PerformanceMetrics;
import io.dongtai.iast.common.entity.performance.metrics.CpuInfoMetrics;
import io.dongtai.iast.common.enums.MetricsKey;
import io.dongtai.iast.common.utils.serialize.SerializeUtils;
import io.dongtai.log.DongTaiLog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 负责监控jvm性能状态，如果达到停止阈值，则停止检测引擎；如果达到卸载阈值，则卸载引擎；
 *
 * @author dongzhiyong@huoxian.cn
 */
public class PerformanceMonitor implements IMonitor {
    private final static IastProperties PROPERTIES = IastProperties.getInstance();
    private static Integer CPU_USAGE = 0;
    private static List<PerformanceMetrics> PERFORMANCE_METRICS = new ArrayList<PerformanceMetrics>();

    private static final String NAME = "PerformanceMonitor";
    private final EngineManager engineManager;
    private final List<MetricsKey> needCollectMetrics = new ArrayList<MetricsKey>();

    @Override
    public String getName() {
        return AgentConstant.THREAD_NAME_PREFIX + NAME;
    }

    public static void setPerformanceMetrics(List<PerformanceMetrics> performanceMetrics) {
        PERFORMANCE_METRICS = performanceMetrics;
    }

    public PerformanceMonitor(EngineManager engineManager) {
        this.engineManager = engineManager;
        configCollectMetrics();
    }

    /**
     * 配置需要收集的指标(todo:通过配置文件初始化)
     */
    private void configCollectMetrics() {
        needCollectMetrics.add(MetricsKey.CPU_USAGE);
        needCollectMetrics.add(MetricsKey.MEM_USAGE);
        needCollectMetrics.add(MetricsKey.MEM_NO_HEAP_USAGE);
        needCollectMetrics.add(MetricsKey.GARBAGE_INFO);
        needCollectMetrics.add(MetricsKey.THREAD_INFO);
    }

    public static Integer getCpuUsage() {
        return CPU_USAGE;
    }

    public static Integer getDiskUsage() {
        try {
            File[] files = File.listRoots();
            for (File file : files) {
                double rate = ((file.getTotalSpace()-file.getUsableSpace())*1.0/file.getTotalSpace())*100;
                return (int) rate;
            }
        }catch (Exception e){
            DongTaiLog.error(e);
        }
        return 0;
    }

    public static List<PerformanceMetrics> getPerformanceMetrics() {
        if (PERFORMANCE_METRICS == null) {
            PERFORMANCE_METRICS = new ArrayList<PerformanceMetrics>();
        }
        return PERFORMANCE_METRICS;
    }

    /**
     * 状态发生转换时，触发engineManager的操作
     * <p>
     * 状态维护：
     * 0 -> 1 -> 0
     */
    @Override
    public void check() throws Exception {
        // 收集性能指标数据
        final List<PerformanceMetrics> performanceMetrics = collectPerformanceMetrics();
        // 更新本地性能指标记录(用于定期上报)
        updatePerformanceMetrics(performanceMetrics);
        // 检查性能指标(用于熔断降级)
        checkPerformanceMetrics(performanceMetrics);
    }

    private void updatePerformanceMetrics(List<PerformanceMetrics> performanceMetrics) {
        for (PerformanceMetrics metrics : performanceMetrics) {
            if (metrics.getMetricsKey() == MetricsKey.CPU_USAGE) {
                final CpuInfoMetrics cpuInfoMetrics = metrics.getMetricsValue(CpuInfoMetrics.class);
                CPU_USAGE = cpuInfoMetrics.getCpuUsagePercentage().intValue();
            }
        }
        PERFORMANCE_METRICS = performanceMetrics;
    }


    /**
     * 收集性能指标
     *
     * @return {@link List}<{@link PerformanceMetrics}>
     */
    private List<PerformanceMetrics> collectPerformanceMetrics() {
        final List<PerformanceMetrics> metricsList = new ArrayList<PerformanceMetrics>();
        for (MetricsKey metricsKey : needCollectMetrics) {
            final MetricsBindCollectorEnum collectorEnum = MetricsBindCollectorEnum.getEnum(metricsKey);
            if (collectorEnum != null) {
                try {
                    IPerformanceCollector collector = collectorEnum.getCollector().newInstance();
                    metricsList.add(collector.getMetrics());
                } catch (Throwable t) {
                    DongTaiLog.error("getPerformanceMetrics failed, collector:{}, err:{}", collectorEnum, t.getMessage());
                }
            }
        }
        return metricsList;
    }

    /**
     * 寻找性能监控熔断器类,反射调用进行性能熔断检查
     */
    private void checkPerformanceMetrics(List<PerformanceMetrics> performanceMetrics) {
        try {
            final Class<?> fallbackManagerClass = EngineManager.getFallbackManagerClass();
            if (fallbackManagerClass == null) {
                return;
            }
            fallbackManagerClass.getMethod("invokePerformanceBreakerCheck", String.class)
                    .invoke(null, SerializeUtils.serializeByList(performanceMetrics));
        } catch (Throwable t) {
            DongTaiLog.error("checkPerformanceMetrics failed, msg:{}, err:{}", t.getMessage(), t.getCause());
        }
    }

    @Override
    public void run() {
        try {
            while (!MonitorDaemonThread.isExit) {
                try {
                    this.check();
                } catch (Throwable t) {
                    DongTaiLog.warn("Monitor thread checked error, monitor:{}, msg:{}, err:{}", getName(), t.getMessage(), t.getCause());
                }
                ThreadUtils.threadSleep(30);
            }
        } catch (Throwable t) {
            DongTaiLog.debug("PerformanceMonitor interrupted, msg:{}", t.getMessage());
        }
    }
}
