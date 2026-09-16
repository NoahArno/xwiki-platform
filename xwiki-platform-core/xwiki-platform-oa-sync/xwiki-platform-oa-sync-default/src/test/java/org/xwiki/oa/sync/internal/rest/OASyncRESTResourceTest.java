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

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.Test;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.oa.sync.internal.excel.OutsourcingExcelImportService;
import org.xwiki.security.authorization.AccessDeniedException;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.Right;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for the admin-only Excel import endpoint of {@link OASyncRESTResource}.
 */
class OASyncRESTResourceTest
{
    private final ContextualAuthorizationManager authorizationManager =
        mock(ContextualAuthorizationManager.class);

    private final OutsourcingExcelImportService importService = mock(OutsourcingExcelImportService.class);

    private final OASyncRESTResource resource = new OASyncRESTResource();

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

        WebApplicationException exception = assertThrows(WebApplicationException.class,
            () -> this.resource.importOutsourcingExcel(new ByteArrayInputStream(new byte[0])));

        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), exception.getResponse().getStatus());
        verify(this.importService, never()).importFrom(any(), any());
    }
}
