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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import org.xwiki.component.annotation.Component;
import org.xwiki.oa.sync.OASyncService;
import org.xwiki.oa.sync.internal.excel.ExcelImportResult;
import org.xwiki.oa.sync.internal.excel.OutsourcingExcelImportService;
import org.xwiki.oa.sync.model.OASyncResult;
import org.xwiki.oa.sync.model.OASyncTriggerType;
import org.xwiki.rest.XWikiResource;
import org.xwiki.rest.XWikiRestComponent;
import org.xwiki.security.authorization.AccessDeniedException;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.Right;

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

    /**
     * 上传体积上限：10 MiB。800 行的 xlsx 通常远小于此，超限只可能是传错文件或恶意构造。
     * <p>
     * {@code WorkbookFactory.create(InputStream)} 是 DOM 式加载，整个文件进内存，
     * 内存占用随文件大小而非行数增长，所以必须有这道闸门。
     */
    private static final int MAX_UPLOAD_BYTES = 10 * 1024 * 1024;

    @Inject
    private OASyncService oaSyncService;

    @Inject
    ContextualAuthorizationManager authorizationManager;

    @Inject
    OutsourcingExcelImportService excelImportService;

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

    /**
     * 上传科技外包用户 Excel 并执行初次全量导入：按 staff_id 建号 + 全部加入「开发中心科技外包组」。
     * 只增量补齐，不动 Excel 之外的用户；重复导入幂等。
     * <p>
     * 用原始字节流而非 multipart：{@code application/octet-stream} 不在 XWiki REST 的
     * 浏览器简单请求类型列表内（{@code PreMatchingRequestFilter}），因而无需 CSRF token。
     *
     * @param body the .xlsx content
     * @return JSON summary of the import
     * @throws Exception if the workbook cannot be parsed
     */
    @POST
    @Path("/outsourcing-excel")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    @Produces(MediaType.APPLICATION_JSON)
    public Response importOutsourcingExcel(InputStream body) throws Exception
    {
        try {
            this.authorizationManager.checkAccess(Right.ADMIN);
        } catch (AccessDeniedException e) {
            // 该端点会创建用户，必须限制为管理员：XWiki.createUser 自身不做任何权限校验
            throw new WebApplicationException("需要管理员权限才能导入外包用户",
                Response.Status.FORBIDDEN);
        }

        XWikiContext context = getXWikiContext();
        try {
            byte[] content = body.readNBytes(MAX_UPLOAD_BYTES + 1);
            if (content.length > MAX_UPLOAD_BYTES) {
                // 按 §7.2：文件级失败要落一条 FAILURE 记录，并返回 400（不是 500）
                this.excelImportService.recordFileFailure(
                    "上传文件超过 " + (MAX_UPLOAD_BYTES / 1024 / 1024) + " MiB 上限", context);
                return badRequest("上传文件超过体积上限");
            }
            ExcelImportResult result =
                this.excelImportService.importFrom(new ByteArrayInputStream(content), context);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", result.getStatus().name());
            response.put("total", result.getTotal());
            response.put("created", result.getCreated());
            response.put("existed", result.getExisted());
            response.put("joinedGroup", result.getJoinedGroup());
            response.put("alreadyMember", result.getAlreadyMember());
            response.put("failed", result.getFailed());
            response.put("errors", result.getErrors());
            return Response.ok(OBJECT_MAPPER.writeValueAsString(response)).build();
        } catch (IOException e) {
            return badRequest("Excel 文件无法解析: " + e.getMessage());
        } finally {
            context.getWiki().getStore().cleanUp(context);
        }
    }

    private Response badRequest(String message) throws Exception
    {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", OASyncResult.Status.FAILURE.name());
        response.put("message", message);
        return Response.status(Response.Status.BAD_REQUEST)
            .entity(OBJECT_MAPPER.writeValueAsString(response))
            .type(MediaType.APPLICATION_JSON).build();
    }
}
