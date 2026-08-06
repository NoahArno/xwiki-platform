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
package org.xwiki.oa.sync.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Result of one source sync run, persisted as a sync record.
 *
 * @version $Id$
 */
public class OASyncResult
{
    /**
     * Overall status of a sync run.
     */
    public enum Status
    {
        /**
         * Everything succeeded.
         */
        SUCCESS,

        /**
         * Some users failed but the run completed.
         */
        PARTIAL_FAILURE,

        /**
         * The whole source failed (FTP/parse level).
         */
        FAILURE
    }

    private final String type;

    private final LocalDate syncDate;

    private final OASyncTriggerType triggerType;

    private final LocalDateTime startTime;

    private Status status;

    private LocalDateTime endTime;

    private int totalCount;

    private int successCount;

    private int failCount;

    private final List<String> errorLog = new ArrayList<>();

    /**
     * @param type the source type (employee / outsourcing)
     * @param syncDate the data date
     * @param triggerType how the sync was triggered
     * @param startTime when the run started
     */
    public OASyncResult(String type, LocalDate syncDate, OASyncTriggerType triggerType, LocalDateTime startTime)
    {
        this.type = type;
        this.syncDate = syncDate;
        this.triggerType = triggerType;
        this.startTime = startTime;
    }

    /**
     * @return the source type
     */
    public String getType()
    {
        return this.type;
    }

    /**
     * @return the data date
     */
    public LocalDate getSyncDate()
    {
        return this.syncDate;
    }

    /**
     * @return the trigger type
     */
    public OASyncTriggerType getTriggerType()
    {
        return this.triggerType;
    }

    /**
     * @return the start time
     */
    public LocalDateTime getStartTime()
    {
        return this.startTime;
    }

    /**
     * @return the overall status
     */
    public Status getStatus()
    {
        return this.status;
    }

    /**
     * @param status the overall status
     */
    public void setStatus(Status status)
    {
        this.status = status;
    }

    /**
     * @return the end time
     */
    public LocalDateTime getEndTime()
    {
        return this.endTime;
    }

    /**
     * @param endTime the end time
     */
    public void setEndTime(LocalDateTime endTime)
    {
        this.endTime = endTime;
    }

    /**
     * @return the total number of processed items
     */
    public int getTotalCount()
    {
        return this.totalCount;
    }

    /**
     * @param totalCount the total number of processed items
     */
    public void setTotalCount(int totalCount)
    {
        this.totalCount = totalCount;
    }

    /**
     * @return the number of successfully synced users
     */
    public int getSuccessCount()
    {
        return this.successCount;
    }

    /**
     * @param successCount the number of successfully synced users
     */
    public void setSuccessCount(int successCount)
    {
        this.successCount = successCount;
    }

    /**
     * @return the number of failed users
     */
    public int getFailCount()
    {
        return this.failCount;
    }

    /**
     * @param failCount the number of failed users
     */
    public void setFailCount(int failCount)
    {
        this.failCount = failCount;
    }

    /**
     * @return the error log lines (parse errors + per-user failures)
     */
    public List<String> getErrorLog()
    {
        return this.errorLog;
    }
}
