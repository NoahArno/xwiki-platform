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

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.component.annotation.Component;
import org.xwiki.oa.sync.internal.mq.DoapUserSynchronizer;
import org.xwiki.oa.sync.internal.store.SyncRecordStore;
import org.xwiki.oa.sync.model.OASyncResult;
import org.xwiki.oa.sync.model.OASyncTriggerType;

import com.xpn.xwiki.XWikiContext;

/**
 * 科技外包用户 Excel 全量导入编排：解析 → 并发建号 → 串行批量入组 → 统计 → 写同步记录。
 * <p>
 * 只增量补齐：建号幂等、入组幂等，不触碰 Excel 之外的用户。
 *
 * @version $Id$
 */
@Component(roles = OutsourcingExcelImportService.class)
@Singleton
public class OutsourcingExcelImportService
{
    /**
     * 同步记录类型。
     */
    public static final String TYPE = "outsourcing-excel";

    private static final Logger LOGGER = LoggerFactory.getLogger(OutsourcingExcelImportService.class);

    @Inject
    OutsourcingExcelParser parser;

    @Inject
    DoapUserSynchronizer synchronizer;

    @Inject
    ExcelImportExecutor executor;

    @Inject
    SyncRecordStore recordStore;

    @Inject
    Provider<XWikiContext> contextProvider;

    /**
     * 导入一个 Excel。
     *
     * @param excel the uploaded workbook content
     * @param context the request XWiki context
     * @return the import statistics
     * @throws IOException if the workbook cannot be parsed at all (a FAILURE record is still written)
     */
    public ExcelImportResult importFrom(InputStream excel, XWikiContext context) throws IOException
    {
        OutsourcingExcelParseResult parsed;
        try {
            parsed = this.parser.parse(excel);
        } catch (IOException e) {
            LOGGER.error("外包 Excel 导入: 解析失败", e);
            saveFailureRecord(e.getMessage(), context);
            throw e;
        }

        ExcelImportResult result = new ExcelImportResult();
        result.setTotal(parsed.getUsernames().size());
        result.getErrors().addAll(parsed.getErrors());
        result.getWarnings().addAll(parsed.getWarnings());
        LOGGER.info("外包 Excel 导入: 解析完成 有效工号=[{}] 行级错误=[{}] 警告=[{}]", parsed.getUsernames().size(),
            parsed.getErrors().size(), parsed.getWarnings().size());

        List<String> usernames = parsed.getUsernames();

        // 阶段 1：并发建号（每个任务有独立的执行上下文与 Hibernate session）
        List<Callable<UserOutcome>> tasks = new ArrayList<>(usernames.size());
        for (String username : usernames) {
            tasks.add(() -> ensureUser(username));
        }
        List<UserOutcome> outcomes = this.executor.runAll(tasks);

        List<String> memberFullNames = new ArrayList<>(usernames.size());
        for (int index = 0; index < usernames.size(); index++) {
            UserOutcome outcome = outcomes.get(index);
            if (outcome == null || outcome.getStatus() == UserOutcome.Status.FAILED) {
                result.incrementFailed();
                result.getErrors().add("用户 [" + usernames.get(index) + "] 建号失败: "
                    + (outcome == null ? "任务超时或异常" : outcome.getMessage()));
                continue;
            }
            if (outcome.getStatus() == UserOutcome.Status.CREATED) {
                result.incrementCreated();
            } else {
                result.incrementExisted();
            }
            memberFullNames.add(outcome.getFullName());
        }

        // 阶段 2：串行批量入组（组文档单点，不能并发写）
        try {
            int added = this.synchronizer.addMembersToOutsourcingGroup(memberFullNames, context);
            result.setJoinedGroup(added);
            result.setAlreadyMember(memberFullNames.size() - added);
        } catch (Exception e) {
            LOGGER.error("外包 Excel 导入: 批量入组失败", e);
            result.getErrors().add("批量加入「开发中心科技外包组」失败: " + e.getMessage());
        }

        saveRecord(result, context);
        LOGGER.info("外包 Excel 导入完成: 总数=[{}] 新建=[{}] 已存在=[{}] 入组=[{}] 已在组=[{}] 失败=[{}]",
            result.getTotal(), result.getCreated(), result.getExisted(), result.getJoinedGroup(),
            result.getAlreadyMember(), result.getFailed());
        return result;
    }

    private UserOutcome ensureUser(String username)
    {
        XWikiContext context = this.contextProvider.get();
        try {
            DoapUserSynchronizer.EnsuredUser ensured = this.synchronizer.ensureUser(username, context);
            return new UserOutcome(username,
                ensured.isCreated() ? UserOutcome.Status.CREATED : UserOutcome.Status.EXISTED,
                ensured.getFullName(), null);
        } catch (Exception e) {
            LOGGER.error("外包 Excel 导入建号失败: [{}]", username, e);
            return new UserOutcome(username, UserOutcome.Status.FAILED, null, e.getMessage());
        }
    }

    private void saveRecord(ExcelImportResult result, XWikiContext context)
    {
        OASyncResult record = new OASyncResult(TYPE, LocalDate.now(), OASyncTriggerType.MANUAL,
            LocalDateTime.now());
        record.setTotalCount(result.getTotal());
        record.setSuccessCount(result.getCreated() + result.getExisted());
        record.setFailCount(result.getFailed());
        record.setStatus(result.getFailed() == 0
            ? OASyncResult.Status.SUCCESS : OASyncResult.Status.PARTIAL_FAILURE);
        record.getErrorLog().addAll(result.getErrors());
        record.getErrorLog().addAll(result.getWarnings());
        record.setEndTime(LocalDateTime.now());
        try {
            this.recordStore.save(record, context);
            this.recordStore.cleanup(TYPE, context);
        } catch (Exception e) {
            LOGGER.error("外包 Excel 导入: 保存同步记录失败", e);
        }
    }

    private void saveFailureRecord(String message, XWikiContext context)
    {
        OASyncResult record = new OASyncResult(TYPE, LocalDate.now(), OASyncTriggerType.MANUAL,
            LocalDateTime.now());
        record.setStatus(OASyncResult.Status.FAILURE);
        record.getErrorLog().add("Excel 解析失败: " + message);
        record.setEndTime(LocalDateTime.now());
        try {
            this.recordStore.save(record, context);
            this.recordStore.cleanup(TYPE, context);
        } catch (Exception e) {
            LOGGER.error("外包 Excel 导入: 保存失败记录失败", e);
        }
    }

    /**
     * 单个工号的处理结果。
     */
    static final class UserOutcome
    {
        /**
         * 建号结果状态。
         */
        enum Status
        {
            /**
             * 本次新建。
             */
            CREATED,

            /**
             * 用户已存在。
             */
            EXISTED,

            /**
             * 处理失败。
             */
            FAILED
        }

        private final String username;

        private final Status status;

        private final String fullName;

        private final String message;

        UserOutcome(String username, Status status, String fullName, String message)
        {
            this.username = username;
            this.status = status;
            this.fullName = fullName;
            this.message = message;
        }

        Status getStatus()
        {
            return this.status;
        }

        String getFullName()
        {
            return this.fullName;
        }

        String getMessage()
        {
            return this.message;
        }

        @Override
        public String toString()
        {
            return "UserOutcome{username='" + this.username + "', status=" + this.status + '}';
        }
    }
}
