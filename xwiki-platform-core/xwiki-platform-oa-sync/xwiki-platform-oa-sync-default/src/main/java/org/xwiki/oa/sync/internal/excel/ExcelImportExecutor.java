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
 * 线程池大小来自 {@code xwiki.oa-sync.excel.threads}；整体等待受
 * {@value #TIMEOUT_MINUTES} 分钟超时约束，超时/异常的任务在结果列表中占位 {@code null}。
 *
 * @version $Id$
 */
@Component(roles = ExcelImportExecutor.class)
@Singleton
public class ExcelImportExecutor implements Initializable, Disposable
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ExcelImportExecutor.class);

    /**
     * 整体等待超时（分钟）：超过后请求直接返回已拿到的部分结果。
     */
    private static final long TIMEOUT_MINUTES = 10;

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    @Inject
    OASyncConfiguration configuration;

    @Inject
    ComponentManager componentManager;

    @Inject
    Provider<XWikiContext> contextProvider;

    private ExecutorService executorService;

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
     * 并发执行全部任务并等待结束，结果与入参顺序一一对应；失败/超时的位置为 {@code null}。
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

        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(TIMEOUT_MINUTES);
        List<T> results = new ArrayList<>(futures.size());
        for (Future<T> future : futures) {
            results.add(await(future, deadline));
        }
        return results;
    }

    private <T> T await(Future<T> future, long deadline)
    {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            LOGGER.error("外包 Excel 导入整体超时，剩余任务结果被丢弃");
            return null;
        }
        try {
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            LOGGER.error("外包 Excel 导入任务超时", e);
            return null;
        } catch (ExecutionException e) {
            LOGGER.error("外包 Excel 导入任务异常", e.getCause());
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
        XWikiContext context = this.contextProvider.get();
        if (context != null && context.getWiki() != null) {
            context.getWiki().getStore().cleanUp(context);
        }
    }

    @Override
    public void dispose() throws ComponentLifecycleException
    {
        if (this.executorService != null) {
            this.executorService.shutdownNow();
            this.executorService = null;
        }
    }
}
