package io.showcase.order.config;

import java.lang.reflect.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * ASYNC PROCESSING on VIRTUAL THREADS.
 *
 * <p>With {@code spring.threads.virtual.enabled=true} (application.yml), Spring Boot backs the
 * auto-configured {@code applicationTaskExecutor} with virtual threads. That executor serves
 * {@code @Async}, {@code @Scheduled} and the messaging listener containers. There's no thread-pool
 * tuning, and blocking I/O is cheap.
 *
 * <p><b>Context propagation:</b> a new thread loses ThreadLocals: the tracing span, the MDC log
 * fields, the security context. Boot applies a {@code TaskDecorator} bean to its executor;
 * {@link ContextPropagatingTaskDecorator} (Micrometer context-propagation) copies the registered
 * contexts, so an {@code @Async} method's logs and spans share the caller's trace id.
 *
 * <p><b>Exceptions in {@code @Async void} methods</b> have no caller to propagate to, so an
 * {@link AsyncUncaughtExceptionHandler} makes sure they're logged.
 *
 * <p><b>Pitfall found while building this project:</b> don't make a {@code @Configuration} class that
 * declares {@code @Bean} methods ALSO implement {@code AsyncConfigurer}. Spring Boot 4 wraps
 * {@code AsyncConfigurer} beans so they fall back to the auto-configured executor, and that wrapper
 * replaces the configuration instance. Its {@code @Bean} factory methods then fail with "object is not
 * an instance of declaring class". Expose the AsyncConfigurer as its OWN bean, as below.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
class AsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    @Bean
    ContextPropagatingTaskDecorator contextPropagatingTaskDecorator() {
        return new ContextPropagatingTaskDecorator();
    }

    /** Only overrides the exception handler; the executor stays Boot's virtual-thread executor. */
    @Bean
    AsyncConfigurer asyncExceptionHandlingConfigurer() {
        return new AsyncConfigurer() {
            @Override
            public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
                return (Throwable ex, Method method, Object... params) ->
                        log.error("Async method {} failed", method.getName(), ex);
            }
        };
    }
}
