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
import java.io.InputStream;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.Test;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.oa.sync.internal.excel.ExcelImportResult;
import org.xwiki.oa.sync.internal.excel.OutsourcingExcelImportService;
import org.xwiki.security.authorization.AccessDeniedException;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.Right;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.store.XWikiStoreInterface;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the admin-only Excel import endpoint of {@link OASyncRESTResource}.
 */
class OASyncRESTResourceTest
{
    private final ContextualAuthorizationManager authorizationManager =
        mock(ContextualAuthorizationManager.class);

    private final OutsourcingExcelImportService importService = mock(OutsourcingExcelImportService.class);

    private final OASyncRESTResource resource = new OASyncRESTResource();

    /**
     * 构造一个能返回可用 {@link XWikiContext} 的资源实例。{@code importOutsourcingExcel} 的
     * {@code finally} 会调用 {@code context.getWiki().getStore().cleanUp(context)}，所以这三层
     * mock 必须串起来，否则 NPE。
     */
    private OASyncRESTResource newResourceWithContext()
    {
        XWikiContext context = mock(XWikiContext.class);
        XWiki wiki = mock(XWiki.class);
        XWikiStoreInterface store = mock(XWikiStoreInterface.class);
        when(context.getWiki()).thenReturn(wiki);
        when(wiki.getStore()).thenReturn(store);

        OASyncRESTResource testableResource = new OASyncRESTResource()
        {
            {
                this.xcontextProvider = () -> context;
            }
        };
        testableResource.authorizationManager = this.authorizationManager;
        testableResource.excelImportService = this.importService;
        return testableResource;
    }

    @Test
    void rejectsNonAdminWithForbidden() throws Exception
    {
        this.resource.authorizationManager = this.authorizationManager;
        this.resource.excelImportService = this.importService;
        // AccessDeniedException 没有 String 构造函数，用真实的引用构造
        doThrow(new AccessDeniedException(Right.ADMIN,
            new DocumentReference("xwiki", "XWiki", "Admin"),
            new DocumentReference("xwiki", "XWiki", "WebHome")))
            .when(this.authorizationManager).checkAccess(Right.ADMIN);

        InputStream body = mock(InputStream.class);
        WebApplicationException exception = assertThrows(WebApplicationException.class,
            () -> this.resource.importOutsourcingExcel(body, MediaType.APPLICATION_OCTET_STREAM));

        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), exception.getResponse().getStatus());
        // 权限闸门必须早于读 body：非管理员被拒时不得消费任何上传内容
        verifyNoInteractions(body);
        verify(this.importService, never()).importFrom(any(), any());
    }

    @Test
    void rejectsUploadsLargerThanTheLimitWithoutImporting() throws Exception
    {
        OASyncRESTResource testableResource = newResourceWithContext();
        byte[] tooBig = new byte[OASyncRESTResource.MAX_UPLOAD_BYTES + 1];

        Response response = testableResource.importOutsourcingExcel(
            new ByteArrayInputStream(tooBig), MediaType.APPLICATION_OCTET_STREAM);

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        // 超限要落一条 FAILURE 记录，且绝不能进入导入
        verify(this.importService).recordFileFailure(anyString(), any());
        verify(this.importService, never()).importFrom(any(), any());
    }

    @Test
    void acceptsUploadExactlyAtTheLimit() throws Exception
    {
        OASyncRESTResource testableResource = newResourceWithContext();
        when(this.importService.importFrom(any(), any())).thenReturn(new ExcelImportResult());
        byte[] atLimit = new byte[OASyncRESTResource.MAX_UPLOAD_BYTES];

        Response response = testableResource.importOutsourcingExcel(
            new ByteArrayInputStream(atLimit), MediaType.APPLICATION_OCTET_STREAM);

        assertEquals(Response.Status.OK.getStatusCode(), response.getStatus());
        verify(this.importService).importFrom(any(), any());
    }

    @Test
    void rejectsRequestWithoutContentType() throws Exception
    {
        OASyncRESTResource testableResource = newResourceWithContext();

        Response response = testableResource.importOutsourcingExcel(new ByteArrayInputStream(new byte[0]), null);

        assertEquals(Response.Status.UNSUPPORTED_MEDIA_TYPE.getStatusCode(), response.getStatus());
        // 415 既不进导入，也不落失败记录（"不走 badRequest、不落记录"）
        verify(this.importService, never()).importFrom(any(), any());
        verify(this.importService, never()).recordFileFailure(any(), any());
    }

    @Test
    void triggerAlsoRejectsNonAdmin() throws Exception
    {
        this.resource.authorizationManager = this.authorizationManager;
        doThrow(new AccessDeniedException(Right.ADMIN,
            new DocumentReference("xwiki", "XWiki", "Admin"),
            new DocumentReference("xwiki", "XWiki", "WebHome")))
            .when(this.authorizationManager).checkAccess(Right.ADMIN);

        WebApplicationException exception = assertThrows(WebApplicationException.class,
            () -> this.resource.trigger(null, null));

        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), exception.getResponse().getStatus());
    }
}
