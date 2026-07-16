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

import java.net.URL;

import javax.servlet.http.HttpSession;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.test.junit5.mockito.InjectMockComponents;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.InjectMockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validate {@link OALoginAction}.
 *
 * @version $Id$
 */
@OldcoreTest
class OALoginActionTest
{
    private static final String OA_KEY = "test-oa-secret-key";
    private static final String PID = "1000";
    private static final String USER_LOGIN_ID = "admin";
    private static final String TIMESTAMP = "1784101050192";

    @InjectMockComponents
    private OALoginAction action;

    @InjectMockitoOldcore
    private MockitoOldcore oldcore;

    @Mock
    private XWikiRequest request;

    @Mock
    private javax.servlet.http.HttpServletRequest httpRequest;

    @Mock
    private XWikiResponse response;

    @Mock
    private HttpSession httpSession;

    @Mock
    private XWikiURLFactory urlFactory;

    @BeforeEach
    void beforeEach() throws Exception
    {
        XWiki xwiki = this.oldcore.getSpyXWiki();
        XWikiContext context = this.oldcore.getXWikiContext();

        // Setup request mock chain
        when(this.request.getHttpServletRequest()).thenReturn(this.httpRequest);
        when(this.httpRequest.getSession(true)).thenReturn(this.httpSession);
        when(this.httpRequest.getSession()).thenReturn(this.httpSession);
        context.setRequest(this.request);
        context.setResponse(this.response);

        // Setup URL factory for redirect
        context.setURLFactory(this.urlFactory);
        when(xwiki.getDefaultSpace(any())).thenReturn("Main");
        when(xwiki.getDefaultPage(any())).thenReturn("WebHome");
        URL mockUrl = new URL("http://localhost/xwiki/bin/view/Main/WebHome");
        when(this.urlFactory.createURL(eq("Main"), eq("WebHome"), eq("view"), any()))
            .thenReturn(mockUrl);

        // Setup OA KEY in config
        when(xwiki.Param("xwiki.authentication.oa.key")).thenReturn(OA_KEY);

        // Register UserAuthenticatedEventNotifier mock
        this.oldcore.getMocker().registerMockComponent(
            com.xpn.xwiki.internal.user.UserAuthenticatedEventNotifier.class);
    }

    // ── Sign verification tests ──

    @Test
    void signVerificationSuccess() throws Exception
    {
        String validSign = DigestUtils.md5Hex(PID + USER_LOGIN_ID + TIMESTAMP + OA_KEY);

        when(this.httpRequest.getParameter("pid")).thenReturn(PID);
        when(this.httpRequest.getParameter("userLoginId")).thenReturn(USER_LOGIN_ID);
        when(this.httpRequest.getParameter("timestamp")).thenReturn(TIMESTAMP);
        when(this.httpRequest.getParameter("sign")).thenReturn(validSign);

        // User exists
        DocumentReference userRef = new DocumentReference("xwiki", "XWiki", USER_LOGIN_ID);
        doReturn(true).when(this.oldcore.getSpyXWiki()).exists(eq(userRef), any());

        boolean result = this.action.action(this.oldcore.getXWikiContext());

        // action() returns false when login succeeds (redirect handled)
        assertEquals(false, result);
    }

    @Test
    void signVerificationFailure() throws Exception
    {
        when(this.httpRequest.getParameter("pid")).thenReturn(PID);
        when(this.httpRequest.getParameter("userLoginId")).thenReturn(USER_LOGIN_ID);
        when(this.httpRequest.getParameter("timestamp")).thenReturn(TIMESTAMP);
        when(this.httpRequest.getParameter("sign")).thenReturn("wrong-sign");

        boolean result = this.action.action(this.oldcore.getXWikiContext());

        assertEquals(true, result);
        assertEquals("签名校验失败",
            this.oldcore.getXWikiContext().get("message"));
    }

    // ── Missing parameter tests ──

    @Test
    void missingParameters() throws Exception
    {
        // pid missing
        when(this.httpRequest.getParameter("pid")).thenReturn(null);
        when(this.httpRequest.getParameter("userLoginId")).thenReturn(USER_LOGIN_ID);
        when(this.httpRequest.getParameter("timestamp")).thenReturn(TIMESTAMP);
        when(this.httpRequest.getParameter("sign")).thenReturn("some-sign");

        boolean result = this.action.action(this.oldcore.getXWikiContext());

        assertEquals(true, result);
        assertEquals("缺少必填参数（pid、userLoginId、timestamp、sign）",
            this.oldcore.getXWikiContext().get("message"));
    }

    // ── User not found test ──

    @Test
    void userNotFound() throws Exception
    {
        String validSign = DigestUtils.md5Hex(PID + USER_LOGIN_ID + TIMESTAMP + OA_KEY);

        when(this.httpRequest.getParameter("pid")).thenReturn(PID);
        when(this.httpRequest.getParameter("userLoginId")).thenReturn(USER_LOGIN_ID);
        when(this.httpRequest.getParameter("timestamp")).thenReturn(TIMESTAMP);
        when(this.httpRequest.getParameter("sign")).thenReturn(validSign);

        // User does NOT exist
        DocumentReference userRef = new DocumentReference("xwiki", "XWiki", USER_LOGIN_ID);
        doReturn(false).when(this.oldcore.getSpyXWiki()).exists(eq(userRef), any());
        when(this.oldcore.getSpyXWiki().search(anyString(), any(), any()))
            .thenReturn(java.util.Collections.emptyList());

        boolean result = this.action.action(this.oldcore.getXWikiContext());

        assertEquals(true, result);
        assertEquals("当前用户 " + USER_LOGIN_ID + " 不存在",
            this.oldcore.getXWikiContext().get("message"));
    }

    // ── OA key not configured test ──

    @Test
    void oaKeyNotConfigured() throws Exception
    {
        when(this.oldcore.getSpyXWiki().Param("xwiki.authentication.oa.key")).thenReturn(null);

        when(this.httpRequest.getParameter("pid")).thenReturn(PID);
        when(this.httpRequest.getParameter("userLoginId")).thenReturn(USER_LOGIN_ID);
        when(this.httpRequest.getParameter("timestamp")).thenReturn(TIMESTAMP);
        when(this.httpRequest.getParameter("sign")).thenReturn("some-sign");

        boolean result = this.action.action(this.oldcore.getXWikiContext());

        assertEquals(true, result);
        assertEquals("系统配置错误：OA 密钥未设置",
            this.oldcore.getXWikiContext().get("message"));
    }

    // ── Render test ──

    @Test
    void renderReturnsTemplateName() throws Exception
    {
        String template = this.action.render(this.oldcore.getXWikiContext());

        assertEquals("oalogin", template);
    }

    @Test
    void renderSets403OnError() throws Exception
    {
        this.oldcore.getXWikiContext().put("message", "some error");

        this.action.render(this.oldcore.getXWikiContext());

        verify(this.response).setStatus(403);
    }
}
