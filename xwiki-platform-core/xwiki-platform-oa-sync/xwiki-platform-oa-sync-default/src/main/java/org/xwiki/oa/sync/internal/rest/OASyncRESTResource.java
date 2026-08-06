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
package org.xwiki.oa.sync.internal.rest;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.xwiki.component.annotation.Component;
import org.xwiki.oa.sync.OASyncService;
import org.xwiki.oa.sync.model.OASyncResult;
import org.xwiki.oa.sync.model.OASyncTriggerType;
import org.xwiki.rest.XWikiResource;
import org.xwiki.rest.XWikiRestComponent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xpn.xwiki.XWikiContext;

/**
 * REST endpoint to manually trigger OA user sync.
 * <p>
 * {@code POST /rest/oa-sync/trigger?type=employee[&date=YYYYMMDD]}
 *
 * @version $Id$
 */
@Component
@Named("org.xwiki.oa.sync.internal.rest.OASyncRESTResource")
@Path("/oa-sync")
public class OASyncRESTResource extends XWikiResource implements XWikiRestComponent
{
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Inject
    private OASyncService oaSyncService;

    /**
     * Trigger a sync run (default data date = yesterday per config; {@code date} overrides for backfill).
     *
     * @param type the source type to await (optional; all enabled sources run anyway)
     * @param date optional data date in YYYYMMDD
     * @return JSON summary of the run
     */
    @POST
    @Path("/trigger")
    @Produces(MediaType.APPLICATION_JSON)
    public Response trigger(@QueryParam("type") String type, @QueryParam("date") String date) throws Exception
    {
        XWikiContext context = getXWikiContext();
        try {
            LocalDate manualDate = null;
            if (date != null && !date.isEmpty()) {
                manualDate = LocalDate.parse(date, DATE_FORMAT);
            }
            List<OASyncResult> results = this.oaSyncService.syncAll(OASyncTriggerType.MANUAL, manualDate);
            OASyncResult selected = null;
            for (OASyncResult result : results) {
                if (result != null && (type == null || type.isEmpty() || type.equals(result.getType()))) {
                    selected = result;
                    break;
                }
            }
            if (selected == null) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("status", "SKIPPED");
                body.put("message", "来源 [" + (type == null ? "?" : type) + "] 未启用或不存在");
                return Response.ok(OBJECT_MAPPER.writeValueAsString(body)).build();
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", selected.getType());
            body.put("syncDate", selected.getSyncDate().toString());
            body.put("triggerType", selected.getTriggerType().name());
            body.put("status", selected.getStatus().name());
            body.put("total", selected.getTotalCount());
            body.put("success", selected.getSuccessCount());
            body.put("failed", selected.getFailCount());
            body.put("errorLog", selected.getErrorLog());
            return Response.ok(OBJECT_MAPPER.writeValueAsString(body)).build();
        } finally {
            context.getWiki().getStore().cleanUp(context);
        }
    }
}
