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
package com.xpn.xwiki.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.xwiki.test.LogLevel;
import org.xwiki.test.junit5.LogCaptureExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link Utils}.
 *
 * @version $Id$
 */
class UtilsTest
{
    @RegisterExtension
    private LogCaptureExtension logCapture = new LogCaptureExtension(LogLevel.WARN);

    @Test
    void getPageWithNoXPage()
    {
        XWikiRequest request = mock(XWikiRequest.class);
        when(request.getParameter("xpage")).thenReturn(null);

        assertEquals("view", Utils.getPage(request, "view"));
    }

    @Test
    void getPageWithLegitValue()
    {
        XWikiRequest request = mock(XWikiRequest.class);
        when(request.getParameter("xpage")).thenReturn("attachment/move");

        assertEquals("attachment/move", Utils.getPage(request, "view"));
    }

    @Test
    void getPageWithTraversalFallsBackToDefault()
    {
        XWikiRequest request = mock(XWikiRequest.class);
        when(request.getParameter("xpage")).thenReturn("../../WEB-INF/xwiki.cfg");

        assertEquals("view", Utils.getPage(request, "view"));
        assertEquals(1, this.logCapture.size());
        assertEquals("Direct access to template [../../WEB-INF/xwiki.cfg] refused. Possible break-in attempt!",
            this.logCapture.getMessage(0));
    }

    @Test
    void getPageWithAbsolutePathFallsBackToDefault()
    {
        XWikiRequest request = mock(XWikiRequest.class);
        when(request.getParameter("xpage")).thenReturn("/etc/passwd");

        assertEquals("view", Utils.getPage(request, "view"));
        assertEquals(1, this.logCapture.size());
        assertEquals("Direct access to template [/etc/passwd] refused. Possible break-in attempt!",
            this.logCapture.getMessage(0));
    }

    @Test
    void getPageWithWindowsSeparatorsFallsBackToDefault()
    {
        XWikiRequest request = mock(XWikiRequest.class);
        when(request.getParameter("xpage")).thenReturn("..\\..\\WEB-INF\\web.xml");

        assertEquals("view", Utils.getPage(request, "view"));
        assertEquals(1, this.logCapture.size());
        assertEquals("Direct access to template [..\\..\\WEB-INF\\web.xml] refused. Possible break-in attempt!",
            this.logCapture.getMessage(0));
    }
}
