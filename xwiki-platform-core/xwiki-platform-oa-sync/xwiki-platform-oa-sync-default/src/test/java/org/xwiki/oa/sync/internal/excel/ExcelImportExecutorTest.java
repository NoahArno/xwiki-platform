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

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.context.Execution;
import org.xwiki.context.ExecutionContext;
import org.xwiki.context.ExecutionContextManager;
import org.xwiki.oa.sync.OASyncConfiguration;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.store.XWikiStoreInterface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ExcelImportExecutor}.
 */
class ExcelImportExecutorTest
{
    private final ComponentManager componentManager = mock(ComponentManager.class);

    private final OASyncConfiguration configuration = mock(OASyncConfiguration.class);

    private final ExecutionContextManager executionContextManager = mock(ExecutionContextManager.class);

    private final Execution execution = mock(Execution.class);

    private final ExcelImportExecutor executor = new ExcelImportExecutor();

    @BeforeEach
    void setUp() throws Exception
    {
        when(this.configuration.getExcelImportThreads()).thenReturn(2);
        when(this.componentManager.getInstance(ExecutionContextManager.class)).thenReturn(this.executionContextManager);
        when(this.componentManager.getInstance(Execution.class)).thenReturn(this.execution);

        this.executor.configuration = this.configuration;
        this.executor.componentManager = this.componentManager;
        this.executor.contextProvider = () -> null;
        this.executor.initialize();
    }

    @AfterEach
    void tearDown() throws Exception
    {
        this.executor.dispose();
    }

    @Test
    void runsEveryTaskOnAPoolThreadWithItsOwnContext() throws Exception
    {
        List<String> results = this.executor.runAll(List.of(
            () -> Thread.currentThread().getName(),
            () -> Thread.currentThread().getName()));

        assertEquals(2, results.size());
        assertTrue(results.get(0).startsWith("oa-excel-import-"), results.get(0));
        assertTrue(results.get(1).startsWith("oa-excel-import-"), results.get(1));
        // 每个任务都初始化并清理一次自己的执行上下文
        verify(this.execution, times(2)).removeContext();
        // 进一步证明两个任务拿到的是**不同**的 ExecutionContext 实例
        ArgumentCaptor<ExecutionContext> captor = ArgumentCaptor.forClass(ExecutionContext.class);
        verify(this.executionContextManager, times(2)).initialize(captor.capture());
        assertNotSame(captor.getAllValues().get(0), captor.getAllValues().get(1));
    }

    @Test
    void returnsTaskResultsInSubmissionOrderEvenWhenCompletionOrderDiffers() throws Exception
    {
        // a 最慢、b 次之、c 立即完成：完成顺序与提交顺序相反，
        // 若实现按“完成顺序”收集结果，这个用例就会失败
        List<String> results = this.executor.runAll(List.of(
            () -> sleepThen(200, "a"),
            () -> sleepThen(100, "b"),
            () -> "c"));

        assertEquals(List.of("a", "b", "c"), results);
    }

    private static String sleepThen(long millis, String value) throws InterruptedException
    {
        Thread.sleep(millis);
        return value;
    }

    @Test
    void returnsNullForFailedTaskWithoutFailingTheOthers() throws Exception
    {
        Callable<String> failing = () -> {
            throw new IllegalStateException("boom");
        };

        List<String> results = this.executor.runAll(List.of(() -> "ok", failing, () -> "ok2"));

        assertEquals(3, results.size());
        assertEquals("ok", results.get(0));
        assertNull(results.get(1));
        assertEquals("ok2", results.get(2));
    }

    @Test
    void runsAllTasksWhenListIsEmpty() throws Exception
    {
        assertTrue(this.executor.runAll(List.of()).isEmpty());
    }

    @Test
    void keepsResultsOfAlreadyFinishedTasksWhenOneTaskTimesOut() throws Exception
    {
        // 回归：futures 是按提交顺序等待的，一个慢任务不能把它后面那些早已成功的任务毒化成 null
        this.executor.timeoutNanos = TimeUnit.MILLISECONDS.toNanos(800);

        List<String> results = this.executor.runAll(List.of(
            () -> sleepThen(3000, "slow"),
            () -> "fast-1",
            () -> "fast-2"));

        assertEquals(3, results.size());
        assertNull(results.get(0), "慢任务应超时为 null");
        assertEquals("fast-1", results.get(1), "早已完成的任务必须被收割");
        assertEquals("fast-2", results.get(2), "早已完成的任务必须被收割");
    }

    @Test
    void cleansUpTheStoreContextOfEveryTaskEvenWhenOneFails() throws Exception
    {
        // 当前 setUp 里 contextProvider 返回 null，只走了生产环境不会走的 context == null 分支；
        // 这里用真实的 mock 链路钉住防 Hibernate 连接泄漏的机制
        XWiki xwiki = mock(XWiki.class);
        XWikiStoreInterface store = mock(XWikiStoreInterface.class);
        XWikiContext xwikiContext = mock(XWikiContext.class);
        when(xwikiContext.getWiki()).thenReturn(xwiki);
        when(xwiki.getStore()).thenReturn(store);
        // 单线程池 → 两个任务串行执行，cleanUp 与 removeContext 的交替顺序才是确定的
        when(this.configuration.getExcelImportThreads()).thenReturn(1);

        ExcelImportExecutor executor = new ExcelImportExecutor();
        executor.configuration = this.configuration;
        executor.componentManager = this.componentManager;
        executor.contextProvider = () -> xwikiContext;
        executor.initialize();

        try {
            List<String> results = executor.runAll(List.of(
                () -> "ok",
                () -> {
                    throw new IllegalStateException("boom");
                }));

            assertEquals(2, results.size());
            assertEquals("ok", results.get(0));
            assertNull(results.get(1));

            // 每个任务（含失败的那个）都必须清理一次
            verify(store, times(2)).cleanUp(xwikiContext);
            verify(this.execution, times(2)).removeContext();

            // 顺序不变量：cleanUp 必须在 ExecutionContextRunnable.removeContext() **之前**。
            // 若把 cleanupStore 挪到 ExecutionContextRunnable 外面，session 已随上下文移除，
            // cleanUp 会静默变成空操作 → 连接泄漏。
            // 单线程池保证两个任务的调用交替是确定的：cleanUp, removeContext, cleanUp, removeContext
            InOrder inOrder = inOrder(store, this.execution);
            inOrder.verify(store).cleanUp(xwikiContext);
            inOrder.verify(this.execution).removeContext();
            inOrder.verify(store).cleanUp(xwikiContext);
            inOrder.verify(this.execution).removeContext();
        } finally {
            executor.dispose();
        }
    }
}
