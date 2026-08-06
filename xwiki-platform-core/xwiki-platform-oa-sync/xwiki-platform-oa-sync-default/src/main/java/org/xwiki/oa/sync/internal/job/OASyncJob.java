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
package org.xwiki.oa.sync.internal.job;

import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.oa.sync.OASyncService;
import org.xwiki.oa.sync.model.OASyncTriggerType;

import com.xpn.xwiki.plugin.scheduler.AbstractJob;
import com.xpn.xwiki.web.Utils;

/**
 * Quartz job triggered by the XWiki scheduler (a SchedulerJobClass document) to run the OA user sync.
 * Not a component: instantiated by Quartz via {@code Class.forName}.
 *
 * @version $Id$
 */
public class OASyncJob extends AbstractJob
{
    private static final Logger LOGGER = LoggerFactory.getLogger(OASyncJob.class);

    @Override
    protected void executeJob(JobExecutionContext jobContext) throws JobExecutionException
    {
        try {
            OASyncService service = Utils.getComponent(OASyncService.class);
            service.syncAll(OASyncTriggerType.SCHEDULED, null);
        } catch (Exception e) {
            LOGGER.error("OA 用户同步定时任务执行失败", e);
            throw new JobExecutionException("OA 用户同步定时任务执行失败", e);
        }
    }
}
