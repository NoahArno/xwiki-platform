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

import org.xwiki.oa.sync.model.OASyncResult;

/**
 * 一次外包 Excel 导入的统计结果。
 *
 * @version $Id$
 */
public class ExcelImportResult
{
    private int total;

    private int created;

    private int existed;

    private int joinedGroup;

    private int alreadyMember;

    private int failed;

    private final List<String> errors = new ArrayList<>();

    private final List<String> warnings = new ArrayList<>();

    /**
     * @return the number of parsed (deduplicated) staff ids
     */
    public int getTotal()
    {
        return this.total;
    }

    /**
     * @param total the number of parsed staff ids
     */
    public void setTotal(int total)
    {
        this.total = total;
    }

    /**
     * @return how many users were created
     */
    public int getCreated()
    {
        return this.created;
    }

    /**
     * Increment the created counter.
     */
    public void incrementCreated()
    {
        this.created++;
    }

    /**
     * @return how many users already existed
     */
    public int getExisted()
    {
        return this.existed;
    }

    /**
     * Increment the already-existed counter.
     */
    public void incrementExisted()
    {
        this.existed++;
    }

    /**
     * @return how many members were actually added to the outsourcing group
     */
    public int getJoinedGroup()
    {
        return this.joinedGroup;
    }

    /**
     * @param joinedGroup how many members were added
     */
    public void setJoinedGroup(int joinedGroup)
    {
        this.joinedGroup = joinedGroup;
    }

    /**
     * @return how many users were already members of the outsourcing group
     */
    public int getAlreadyMember()
    {
        return this.alreadyMember;
    }

    /**
     * @param alreadyMember how many users were already members
     */
    public void setAlreadyMember(int alreadyMember)
    {
        this.alreadyMember = alreadyMember;
    }

    /**
     * @return how many users failed
     */
    public int getFailed()
    {
        return this.failed;
    }

    /**
     * Increment the failure counter.
     */
    public void incrementFailed()
    {
        this.failed++;
    }

    /**
     * @return the errors (fail the import)
     */
    public List<String> getErrors()
    {
        return this.errors;
    }

    /**
     * @return the warnings (informational only)
     */
    public List<String> getWarnings()
    {
        return this.warnings;
    }

    /**
     * 导入状态：只要有 error（行级解析错误、逐用户失败、或批量入组整体失败）就是 {@code PARTIAL_FAILURE}。
     * <p>
     * 刻意**不能**只看 {@link #getFailed()}：行级解析错误与"批量入组整体失败"都不会增加 {@code failed}，
     * 若按 {@code failed == 0} 判成功，就会出现"一个成员都没加进组，却报 SUCCESS"的静默丢权限，
     * 以及"整表所有行都解析失败，却报 SUCCESS(0)"。
     *
     * @return {@code PARTIAL_FAILURE} if any error was recorded, otherwise {@code SUCCESS}；
     *         该方法**永不返回 {@code FAILURE}** —— 文件级失败（解析不出、建号阶段整体失败）
     *         由调用方另写一条 {@code FAILURE} 记录，不经过这里
     */
    public OASyncResult.Status getStatus()
    {
        return this.errors.isEmpty() ? OASyncResult.Status.SUCCESS : OASyncResult.Status.PARTIAL_FAILURE;
    }
}
