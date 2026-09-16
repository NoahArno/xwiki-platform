/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.oa.sync.internal.excel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentLifecycleException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.component.phase.Disposable;
import org.xwiki.component.phase.Initializable;
import org.xwiki.context.concurrent.ExecutionContextRunnable;
import org.xwiki.oa.sync.OASyncConfiguration;

import com.xpn.xwiki.XWikiContext;

/**
 * 外包 Excel 导入的并发执行器：把一个工号的处理任务丢进有界线程池，
 * 每个任务拥有独立的 XWiki 执行上下文（因而有独立的 Hibernate session），
 * 任务结束后释放该上下文的存储会话。
 * <p>
 * 线程池大小来自 {@code xwiki.oa-sync.excel.threads}；整体等待受默认 10 分钟超时约束，
 * 超时/异常的任务在结果列表中占位 {@code null}。
 *
 * @version $Id$
 */
@Component(roles = ExcelImportExecutor.class)
@Singleton
public class ExcelImportExecutor implements Initializable, Disposable
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ExcelImportExecutor.class);

    /**
     * 整体等待超时（纳秒），默认 10 分钟。
     * <p>
     * 刻意声明为包级可见的**非 final** 字段：若写成 {@code private static final long} 并参与
     * {@code TimeUnit.MINUTES.toNanos(...)} 这类编译期常量表达式，值会被内联进字节码，
     * 连反射都改不了，单测无法注入更短的超时，超时分支就永远测不到
     * （该分支曾因此潜伏了一个"丢弃已完成结果"的缺陷）。
     */
    long timeoutNanos = TimeUnit.MINUTES.toNanos(10);

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    @Inject
    OASyncConfiguration configuration;

    @Inject
    ComponentManager componentManager;

    @Inject
    Provider<XWikiContext> contextProvider;

    private ExecutorService executorService;

    /**
     * 创建常驻线程池。
     * <p>
     * 已知取舍：线程池由单例组件持有、只在初始化时按
     * {@code xwiki.oa-sync.excel.threads} 建一次，因此改该配置后必须重启 XWiki 才生效。
     */
    @Override
    public void initialize()
    {
        int threads = this.configuration.getExcelImportThreads();
        this.executorService = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "oa-excel-import-" + THREAD_COUNTER.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        LOGGER.info("外包 Excel 导入线程池已初始化: threads=[{}]", threads);
    }

    /**
     * 并发执行全部任务并等待结束，结果与入参顺序一一对应；失败/超时/未等待到的位置为 {@code null}。
     *
     * @param tasks the per-user tasks
     * @param <T> the task result type
     * @return one result per task, in the same order
     */
    public <T> List<T> runAll(List<Callable<T>> tasks)
    {
        List<Future<T>> futures = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks) {
            futures.add(this.executorService.submit(contextualize(task)));
        }

        long deadline = System.nanoTime() + this.timeoutNanos;
        List<T> results = new ArrayList<>(futures.size());
        for (int index = 0; index < futures.size(); index++) {
            Future<T> future = futures.get(index);
            if (future.isDone()) {
                // 已完成的任务无论 deadline 是否到期都必须收割：futures 是按提交顺序等待的，
                // 一个慢任务不能把它后面那些早已成功的任务一起毒化成 null
                results.add(harvest(future, index));
                continue;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                LOGGER.error("外包 Excel 导入整体超时，未完成任务数=[{}]", futures.size() - index);
                collectRemaining(futures, index, results);
                break;
            }
            results.add(await(future, remaining, index));
        }
        return results;
    }

    /**
     * 整体超时后的收尾：已完成的任务照常收割，尚未开始的取消掉，已在运行的放弃等待。
     *
     * @param futures the submitted futures
     * @param fromIndex the first index still outstanding
     * @param results the result list to fill, mutated in place
     * @param <T> the task result type
     */
    private <T> void collectRemaining(List<Future<T>> futures, int fromIndex, List<T> results)
    {
        for (int index = fromIndex; index < futures.size(); index++) {
            Future<T> future = futures.get(index);
            if (future.isDone() && !future.isCancelled()) {
                results.add(harvest(future, index));
            } else {
                // 取消还没开始跑的任务，避免 HTTP 响应结束后它们仍在后台继续；已在运行的取消不掉，
                // 只能放弃等待（建号幂等，重跑即可补上）
                future.cancel(false);
                results.add(null);
            }
        }
    }

    private <T> T await(Future<T> future, long remaining, int index)
    {
        try {
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            LOGGER.error("外包 Excel 导入任务超时: index=[{}]", index);
            return null;
        } catch (ExecutionException e) {
            LOGGER.error("外包 Excel 导入任务异常: index=[{}]", index, e.getCause());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("外包 Excel 导入等待被中断，剩余任务不再等待: index=[{}]", index);
            return null;
        }
    }

    /**
     * 收割一个已经结束的任务的结果；异常与取消都回落为 {@code null}。
     *
     * @param future an already finished future
     * @param index the task index, for logging
     * @param <T> the task result type
     * @return the task result, or {@code null} if it failed or was cancelled
     */
    private <T> T harvest(Future<T> future, int index)
    {
        try {
            return future.get();
        } catch (ExecutionException e) {
            LOGGER.error("外包 Excel 导入任务异常: index=[{}]", index, e.getCause());
            return null;
        } catch (CancellationException e) {
            LOGGER.warn("外包 Excel 导入任务已被取消: index=[{}]", index);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * 把任务包进一个独立的执行上下文，并在结束时释放该上下文的存储会话。
     * <p>
     * {@link ExecutionContextRunnable#run()} 只负责移除上下文（{@code DefaultExecution.removeContext()}
     * 仅清 ThreadLocal），因此这里额外调用 {@code store.cleanUp(context)} 结束事务、释放连接。
     */
    private <T> Callable<T> contextualize(Callable<T> task)
    {
        return () -> {
            AtomicReference<T> value = new AtomicReference<>();
            AtomicReference<RuntimeException> failure = new AtomicReference<>();

            new ExecutionContextRunnable(() -> {
                try {
                    value.set(task.call());
                } catch (Exception e) {
                    failure.set(new RuntimeException(e));
                } finally {
                    cleanupStore();
                }
            }, this.componentManager).run();

            if (failure.get() != null) {
                throw failure.get();
            }
            return value.get();
        };
    }

    private void cleanupStore()
    {
        try {
            XWikiContext context = this.contextProvider.get();
            if (context != null && context.getWiki() != null) {
                context.getWiki().getStore().cleanUp(context);
            }
        } catch (Exception e) {
            // 本方法在 finally 里执行：若异常抛出会覆盖任务本身的异常，且让 failure 永远读不到，
            // 原始根因被彻底丢弃。因此这里只记录，绝不改变任务结果。
            LOGGER.error("外包 Excel 导入: 清理会话失败，可能泄漏连接", e);
        }
    }

    @Override
    public void dispose() throws ComponentLifecycleException
    {
        if (this.executorService != null) {
            // 刻意不置 null：置 null 会让 dispose 之后再调 runAll 变成裸 NPE，
            // 保留则 submit 会自然抛 RejectedExecutionException，更容易定位
            this.executorService.shutdownNow();
        }
    }
}
