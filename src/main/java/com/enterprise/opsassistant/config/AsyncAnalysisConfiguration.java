package com.enterprise.opsassistant.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 告警流式分析使用的异步线程池配置。
 *
 * <p>SSE Controller 必须尽快把连接返回给客户端，不能在 Tomcat 请求线程中同步完成整条分析。
 * 独立线程池还可以把耗时分析与普通健康检查、静态资源请求隔离，避免分析流量耗尽 Web 线程。</p>
 */
@Configuration
public class AsyncAnalysisConfiguration {

    /**
     * 创建有明确容量边界的分析线程池。
     *
     * <p>核心线程 2、最大线程 8、等待队列 50 适合作业演示。生产环境应根据模型响应时间、
     * 工具延迟和机器容量通过配置文件调整，不能直接使用无限线程或无限队列。</p>
     */
    @Bean(name = "analysisTaskExecutor")
    public Executor analysisTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("alert-analysis-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
