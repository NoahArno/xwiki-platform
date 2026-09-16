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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.context.Execution;
import org.xwiki.context.ExecutionContextManager;
import org.xwiki.oa.sync.OASyncConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        verify(this.executionContextManager, times(2)).initialize(org.mockito.ArgumentMatchers.any());
        verify(this.execution, times(2)).removeContext();
    }

    @Test
    void returnsTaskResultsInSubmissionOrder() throws Exception
    {
        List<String> results = this.executor.runAll(List.of(
            () -> "a",
            () -> "b",
            () -> "c"));

        assertEquals(List.of("a", "b", "c"), results);
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
}
