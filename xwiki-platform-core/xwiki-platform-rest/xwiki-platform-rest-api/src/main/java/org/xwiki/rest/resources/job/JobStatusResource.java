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
package org.xwiki.rest.resources.job;

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;

import org.xwiki.rest.XWikiRestException;
import org.xwiki.rest.model.jaxb.JobStatus;

/**
 * @version $Id$
 * @since 7.2M3
 */
@Path("/" + JobStatusResource.NAME + "/{jobId: .+}")
public interface JobStatusResource
{
    /**
     * The entry name of the resource.
     * 
     * @since 8.0
     */
    String NAME = "jobstatus";

    /**
     * Find and return the status of the running job (of the stored status if the job is finished).
     * 
     * @param jobId the identifier of the job
     * @param request if {@code True} the job request should be serialized in the status (since 9.1RC1)
     * @param progress if {@code True} the job progress should be serialized in the status (since 9.1RC1)
     * @param log if {@code True} the job log should be serialized in the status (since 9.1RC1)
     * @param logFromLevel the level of the log to filter from
     * @return the job status
     * @throws XWikiRestException when failing to search job
     */
    @GET
    JobStatus getJobStatus(@PathParam("jobId") String jobId,
        @QueryParam("request") @DefaultValue("false") boolean request,
        @QueryParam("progress") @DefaultValue("true") boolean progress,
        @QueryParam("log") @DefaultValue("false") boolean log, @QueryParam("log_fromLevel") String logFromLevel)
        throws XWikiRestException;
}
