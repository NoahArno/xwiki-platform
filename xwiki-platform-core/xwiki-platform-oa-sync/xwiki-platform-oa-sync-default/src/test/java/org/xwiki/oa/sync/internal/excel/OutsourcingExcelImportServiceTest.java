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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.oa.sync.internal.mq.DoapUserSynchronizer;
import org.xwiki.oa.sync.internal.store.SyncRecordStore;
import org.xwiki.oa.sync.model.OASyncResult;

import com.xpn.xwiki.XWikiContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OutsourcingExcelImportService}.
 */
class OutsourcingExcelImportServiceTest
{
    private final OutsourcingExcelParser parser = mock(OutsourcingExcelParser.class);

    private final DoapUserSynchronizer synchronizer = mock(DoapUserSynchronizer.class);

    private final ExcelImportExecutor executor = mock(ExcelImportExecutor.class);

    private final SyncRecordStore recordStore = mock(SyncRecordStore.class);

    private final XWikiContext context = mock(XWikiContext.class);

    private final OutsourcingExcelImportService service = new OutsourcingExcelImportService();

    @BeforeEach
    void setUp() throws Exception
    {
        this.service.parser = this.parser;
        this.service.synchronizer = this.synchronizer;
        this.service.executor = this.executor;
        this.service.recordStore = this.recordStore;
        this.service.contextProvider = () -> this.context;

        // 执行器改为同步内联执行，使编排逻辑可确定性验证
        doAnswer(invocation -> {
            List<Callable<?>> tasks = invocation.getArgument(0);
            List<Object> results = new ArrayList<>(tasks.size());
            for (Callable<?> task : tasks) {
                results.add(task.call());
            }
            return results;
        }).when(this.executor).runAll(anyList());
    }

    @Test
    void countsCreatedExistedAndJoinedMembers() throws Exception
    {
        parseReturns("T001", "T002", "T003");
        when(this.synchronizer.ensureUser(eq("T001"), any()))
            .thenReturn(new DoapUserSynchronizer.EnsuredUser("XWiki.T001", true));
        when(this.synchronizer.ensureUser(eq("T002"), any()))
            .thenReturn(new DoapUserSynchronizer.EnsuredUser("XWiki.T002", false));
        when(this.synchronizer.ensureUser(eq("T003"), any()))
            .thenReturn(new DoapUserSynchronizer.EnsuredUser("XWiki.T003", true));
        when(this.synchronizer.addMembersToOutsourcingGroup(anyCollection(), any())).thenReturn(2);

        ExcelImportResult result = this.service.importFrom(stream(), this.context);

        assertEquals(3, result.getTotal());
        assertEquals(2, result.getCreated());
        assertEquals(1, result.getExisted());
        assertEquals(2, result.getJoinedGroup());
        assertEquals(1, result.getAlreadyMember());
        assertEquals(0, result.getFailed());
        assertTrue(result.getErrors().isEmpty());
    }

    @Test
    void isolatesAFailingUserAndStillProcessesTheRest() throws Exception
    {
        parseReturns("T001", "T002");
        when(this.synchronizer.ensureUser(eq("T001"), any()))
            .thenReturn(new DoapUserSynchronizer.EnsuredUser("XWiki.T001", true));
        when(this.synchronizer.ensureUser(eq("T002"), any()))
            .thenThrow(new RuntimeException("建号失败"));
        when(this.synchronizer.addMembersToOutsourcingGroup(anyCollection(), any())).thenReturn(1);

        ExcelImportResult result = this.service.importFrom(stream(), this.context);

        assertEquals(2, result.getTotal());
        assertEquals(1, result.getCreated());
        assertEquals(1, result.getFailed());
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).contains("T002"));
        // 失败用户不进组
        verify(this.synchronizer).addMembersToOutsourcingGroup(eq(List.of("XWiki.T001")), eq(this.context));
    }

    @Test
    void writesSyncRecordWithPartialFailureStatus() throws Exception
    {
        parseReturns("T001");
        when(this.synchronizer.ensureUser(eq("T001"), any()))
            .thenThrow(new RuntimeException("建号失败"));
        when(this.synchronizer.addMembersToOutsourcingGroup(anyCollection(), any())).thenReturn(0);

        this.service.importFrom(stream(), this.context);

        org.mockito.ArgumentCaptor<OASyncResult> captor =
            org.mockito.ArgumentCaptor.forClass(OASyncResult.class);
        verify(this.recordStore).save(captor.capture(), eq(this.context));
        assertEquals(OutsourcingExcelImportService.TYPE, captor.getValue().getType());
        assertEquals(OASyncResult.Status.PARTIAL_FAILURE, captor.getValue().getStatus());
        verify(this.recordStore).cleanup(OutsourcingExcelImportService.TYPE, this.context);
    }

    @Test
    void writesSyncRecordWithSuccessStatusWhenAllGood() throws Exception
    {
        parseReturns("T001");
        when(this.synchronizer.ensureUser(eq("T001"), any()))
            .thenReturn(new DoapUserSynchronizer.EnsuredUser("XWiki.T001", true));
        when(this.synchronizer.addMembersToOutsourcingGroup(anyCollection(), any())).thenReturn(1);

        this.service.importFrom(stream(), this.context);

        org.mockito.ArgumentCaptor<OASyncResult> captor =
            org.mockito.ArgumentCaptor.forClass(OASyncResult.class);
        verify(this.recordStore).save(captor.capture(), eq(this.context));
        assertEquals(OASyncResult.Status.SUCCESS, captor.getValue().getStatus());
    }

    @Test
    void propagatesParseFailureAndRecordsItAsFailure() throws Exception
    {
        when(this.parser.parse(any(InputStream.class))).thenThrow(new IOException("不是合法的工作簿"));

        try {
            this.service.importFrom(stream(), this.context);
            org.junit.jupiter.api.Assertions.fail("应当抛出 IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("不是合法的工作簿"));
        }

        org.mockito.ArgumentCaptor<OASyncResult> captor =
            org.mockito.ArgumentCaptor.forClass(OASyncResult.class);
        verify(this.recordStore).save(captor.capture(), eq(this.context));
        assertEquals(OASyncResult.Status.FAILURE, captor.getValue().getStatus());
        verify(this.synchronizer, never()).ensureUser(any(), any());
    }

    @Test
    void treatsNullOutcomeAsFailureAndNeverReportsSuccess() throws Exception
    {
        // runAll 的 null 同时表示"确定失败""合法 null 返回""整体超时没等到"三种含义，
        // 编排层无法区分，因此一律计失败；且必须判空后再调 getStatus()，否则 NPE
        parseReturns("T001", "T002");
        when(this.synchronizer.ensureUser(eq("T001"), any()))
            .thenReturn(new DoapUserSynchronizer.EnsuredUser("XWiki.T001", true));
        when(this.synchronizer.ensureUser(eq("T002"), any()))
            .thenReturn(new DoapUserSynchronizer.EnsuredUser("XWiki.T002", true));
        when(this.synchronizer.addMembersToOutsourcingGroup(anyCollection(), any())).thenReturn(1);

        // 直接让 executor.runAll 返回含 null 的结果，模拟"超时没等到"
        doAnswer(invocation -> {
            List<Callable<?>> tasks = invocation.getArgument(0);
            List<Object> results = new ArrayList<>();
            results.add(tasks.get(0).call());
            results.add(null);
            return results;
        }).when(this.executor).runAll(anyList());

        ExcelImportResult result = this.service.importFrom(stream(), this.context);

        assertEquals(2, result.getTotal());
        assertEquals(1, result.getCreated());
        assertEquals(1, result.getFailed());
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).contains("T002"), result.getErrors().toString());

        // null 槽位不得进组
        verify(this.synchronizer).addMembersToOutsourcingGroup(eq(List.of("XWiki.T001")), eq(this.context));

        // 存在 null 时绝不能判 SUCCESS
        org.mockito.ArgumentCaptor<OASyncResult> captor =
            org.mockito.ArgumentCaptor.forClass(OASyncResult.class);
        verify(this.recordStore).save(captor.capture(), eq(this.context));
        assertEquals(OASyncResult.Status.PARTIAL_FAILURE, captor.getValue().getStatus());
    }

    @Test
    void treatsGroupWriteFailureAsPartialFailure() throws Exception
    {
        // 回归：入组整体失败原先只往 errors 加一条、不改 failed，于是被判成 SUCCESS ——
        // 组里一个成员都没加进去，管理员却收到绿色的"导入完成"
        parseReturns("T001");
        when(this.synchronizer.ensureUser(eq("T001"), any()))
            .thenReturn(new DoapUserSynchronizer.EnsuredUser("XWiki.T001", true));
        when(this.synchronizer.addMembersToOutsourcingGroup(anyCollection(), any()))
            .thenThrow(new RuntimeException("组保存失败"));

        ExcelImportResult result = this.service.importFrom(stream(), this.context);

        assertEquals(0, result.getFailed());
        assertEquals(OASyncResult.Status.PARTIAL_FAILURE, result.getStatus());
        assertEquals(1, result.getErrors().size());

        org.mockito.ArgumentCaptor<OASyncResult> captor =
            org.mockito.ArgumentCaptor.forClass(OASyncResult.class);
        verify(this.recordStore).save(captor.capture(), eq(this.context));
        assertEquals(OASyncResult.Status.PARTIAL_FAILURE, captor.getValue().getStatus());
        // failCount 是"失败用户数"（api 契约 + 与 FTP 链路一致），不是 error 条目数：
        // 入组整体失败时 failed == 0，但状态仍是 PARTIAL_FAILURE（运行级）
        assertEquals(0, captor.getValue().getFailCount());
    }

    @Test
    void treatsRowLevelParseErrorsAsPartialFailure() throws Exception
    {
        parseReturnsWithErrors(List.of("第 2 行 staff_id 为空"), "T001");
        when(this.synchronizer.ensureUser(eq("T001"), any()))
            .thenReturn(new DoapUserSynchronizer.EnsuredUser("XWiki.T001", true));
        when(this.synchronizer.addMembersToOutsourcingGroup(anyCollection(), any())).thenReturn(1);

        ExcelImportResult result = this.service.importFrom(stream(), this.context);

        assertEquals(OASyncResult.Status.PARTIAL_FAILURE, result.getStatus());
    }

    @Test
    void writesParseErrorsAndWarningsIntoTheRecord() throws Exception
    {
        parseReturnsWithErrorsAndWarnings(List.of("第 2 行 staff_id 为空"),
            List.of("第 3 行 staff_id 重复，已跳过: T001"), "T001");
        when(this.synchronizer.ensureUser(eq("T001"), any()))
            .thenReturn(new DoapUserSynchronizer.EnsuredUser("XWiki.T001", true));
        when(this.synchronizer.addMembersToOutsourcingGroup(anyCollection(), any())).thenReturn(1);

        this.service.importFrom(stream(), this.context);

        org.mockito.ArgumentCaptor<OASyncResult> captor =
            org.mockito.ArgumentCaptor.forClass(OASyncResult.class);
        verify(this.recordStore).save(captor.capture(), eq(this.context));
        String errorLog = String.join("\n", captor.getValue().getErrorLog());
        assertTrue(errorLog.contains("[错误] 第 2 行 staff_id 为空"), errorLog);
        assertTrue(errorLog.contains("[警告] 第 3 行 staff_id 重复，已跳过: T001"), errorLog);
        // 警告不翻转状态，但错误会
        assertEquals(OASyncResult.Status.PARTIAL_FAILURE, captor.getValue().getStatus());
    }

    @Test
    void writesExactlyOneFailureRecordWhenTheExecutorBlowsUp() throws Exception
    {
        parseReturns("T001", "T002");
        when(this.executor.runAll(anyList()))
            .thenThrow(new java.util.concurrent.RejectedExecutionException("线程池已关闭"));

        try {
            this.service.importFrom(stream(), this.context);
            org.junit.jupiter.api.Assertions.fail("应当向上抛出");
        } catch (RuntimeException expected) {
            assertTrue(expected instanceof java.util.concurrent.RejectedExecutionException,
                expected.toString());
        }

        // 恰好一条记录，不能重复写
        org.mockito.ArgumentCaptor<OASyncResult> captor =
            org.mockito.ArgumentCaptor.forClass(OASyncResult.class);
        verify(this.recordStore, times(1)).save(captor.capture(), eq(this.context));
        assertEquals(OASyncResult.Status.FAILURE, captor.getValue().getStatus());
        assertEquals(2, captor.getValue().getTotalCount());
        // 整体失败时一个用户都没处理成功，按 total = success + fail 的口径未处理者计入失败
        assertEquals(2, captor.getValue().getFailCount());
        verify(this.synchronizer, never()).ensureUser(any(), any());
    }

    @Test
    void treatsMissingOutcomeSlotsAsFailuresWithoutBlowingUp() throws Exception
    {
        parseReturns("T001", "T002");
        when(this.synchronizer.ensureUser(eq("T001"), any()))
            .thenReturn(new DoapUserSynchronizer.EnsuredUser("XWiki.T001", true));
        when(this.synchronizer.addMembersToOutsourcingGroup(anyCollection(), any())).thenReturn(1);

        // 让 runAll 少返回一个槽位，模拟长度耦合被打破
        doAnswer(invocation -> {
            List<Callable<?>> tasks = invocation.getArgument(0);
            List<Object> results = new ArrayList<>();
            results.add(tasks.get(0).call());
            return results;
        }).when(this.executor).runAll(anyList());

        ExcelImportResult result = this.service.importFrom(stream(), this.context);

        assertEquals(2, result.getTotal());
        assertEquals(1, result.getCreated());
        assertEquals(1, result.getFailed());
        assertEquals(OASyncResult.Status.PARTIAL_FAILURE, result.getStatus());
        // 缺失槽位不得进组
        verify(this.synchronizer).addMembersToOutsourcingGroup(eq(List.of("XWiki.T001")), eq(this.context));
    }

    private void parseReturns(String... usernames) throws IOException
    {
        OutsourcingExcelParseResult parsed = new OutsourcingExcelParseResult();
        parsed.getUsernames().addAll(List.of(usernames));
        when(this.parser.parse(any(InputStream.class))).thenAnswer(invocation -> parsed);
    }

    private void parseReturnsWithErrors(List<String> errors, String... usernames) throws IOException
    {
        OutsourcingExcelParseResult parsed = new OutsourcingExcelParseResult();
        parsed.getUsernames().addAll(List.of(usernames));
        parsed.getErrors().addAll(errors);
        when(this.parser.parse(any(InputStream.class))).thenAnswer(invocation -> parsed);
    }

    private void parseReturnsWithErrorsAndWarnings(List<String> errors, List<String> warnings,
        String... usernames) throws IOException
    {
        OutsourcingExcelParseResult parsed = new OutsourcingExcelParseResult();
        parsed.getUsernames().addAll(List.of(usernames));
        parsed.getErrors().addAll(errors);
        parsed.getWarnings().addAll(warnings);
        when(this.parser.parse(any(InputStream.class))).thenAnswer(invocation -> parsed);
    }

    private InputStream stream()
    {
        return new ByteArrayInputStream(new byte[] { 1, 2, 3 });
    }
}
