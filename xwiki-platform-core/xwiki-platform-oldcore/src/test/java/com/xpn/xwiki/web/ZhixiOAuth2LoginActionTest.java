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
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpSession;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.xwiki.model.reference.DocumentReference;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.InjectMockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validate {@link ZhixiOAuth2LoginAction}.
 *
 * @version $Id$
 */
@OldcoreTest
class ZhixiOAuth2LoginActionTest
{
    private static final String DOAP_HOST = "http://sit-doap.mis.bcs";
    private static final String CLIENT_ID = "test-client-id";
    private static final String CLIENT_SECRET = "test-client-secret";
    private static final String REDIRECT_URI = "http://xwiki.example.com/xwiki/zhixi-login";
    private static final String USER_LOGIN_ID = "admin";
    private static final String AUTH_CODE = "test-auth-code-123";
    private static final String ACCESS_TOKEN = "test-access-token-456";
    private static final String STATE = "test-state-value";

    @InjectMockitoOldcore
    private MockitoOldcore oldcore;

    @Mock
    private jakarta.servlet.http.HttpServletRequest httpRequest;

    @Mock
    private jakarta.servlet.http.HttpServletResponse httpResponse;

    @Mock
    private HttpSession httpSession;

    @Mock
    private java.io.PrintWriter printWriter;

    @Mock
    private XWikiURLFactory urlFactory;

    private ZhixiOAuth2LoginAction action;

    @BeforeEach
    void beforeEach() throws Exception
    {
        XWiki xwiki = this.oldcore.getSpyXWiki();

        // Setup request-session mock chain
        when(this.httpRequest.getSession(true)).thenReturn(this.httpSession);
        when(this.httpRequest.getSession(false)).thenReturn(this.httpSession);

        // Setup basic XWiki config
        when(xwiki.Param("xwiki.authentication.zhixi.doap-host")).thenReturn(DOAP_HOST);
        when(xwiki.Param("xwiki.authentication.zhixi.client-id")).thenReturn(CLIENT_ID);
        when(xwiki.Param("xwiki.authentication.zhixi.client-secret")).thenReturn(CLIENT_SECRET);
        when(xwiki.Param("xwiki.authentication.zhixi.redirect-uri")).thenReturn(REDIRECT_URI);

        // Setup URL factory for redirect via XWikiContext
        when(xwiki.getDefaultSpace(any())).thenReturn("Main");
        when(xwiki.getDefaultPage(any())).thenReturn("WebHome");
        URL mockUrl = new URL("http://localhost/xwiki/bin/view/Main/WebHome");
        when(this.urlFactory.createURL(eq("Main"), eq("WebHome"), eq("view"), any())).thenReturn(mockUrl);
        this.oldcore.getXWikiContext().setURLFactory(this.urlFactory);

        // Create servlet as a spy to mock XWiki context initialization and DOAP HTTP calls
        this.action = spy(new ZhixiOAuth2LoginAction());
        doReturn(this.oldcore.getXWikiContext())
            .when(this.action).initializeXWikiContext(any(), any());
    }

    // ── Authorization flow ──

    @Test
    void startAuthorizationRedirectsToDoap() throws Exception
    {
        when(this.httpRequest.getParameter("code")).thenReturn(null);
        when(this.httpRequest.getParameter("state")).thenReturn(null);
        when(this.httpRequest.getRequestURL()).thenReturn(new StringBuffer(REDIRECT_URI));

        this.action.doGet(this.httpRequest, this.httpResponse);

        verify(this.httpSession).setAttribute(eq("zhixi_oauth2_state"), anyString());
        verify(this.httpResponse).sendRedirect(anyString());
    }

    @Test
    void startAuthorizationMissingConfigReturns403() throws Exception
    {
        when(this.oldcore.getSpyXWiki().Param("xwiki.authentication.zhixi.doap-host")).thenReturn(null);
        when(this.oldcore.getSpyXWiki().Param("xwiki.authentication.zhixi.client-id")).thenReturn(null);

        when(this.httpRequest.getParameter("code")).thenReturn(null);
        when(this.httpRequest.getParameter("state")).thenReturn(null);
        when(this.httpResponse.getWriter()).thenReturn(this.printWriter);

        this.action.doGet(this.httpRequest, this.httpResponse);

        verify(this.httpResponse).setStatus(403);
    }

    // ── State validation (CSRF) ──

    @Test
    void callbackWithStateMismatchReturns403() throws Exception
    {
        when(this.httpRequest.getParameter("code")).thenReturn(AUTH_CODE);
        when(this.httpRequest.getParameter("state")).thenReturn("different-state");
        when(this.httpSession.getAttribute("zhixi_oauth2_state")).thenReturn(STATE);
        when(this.httpResponse.getWriter()).thenReturn(this.printWriter);

        this.action.doGet(this.httpRequest, this.httpResponse);

        verify(this.httpResponse).setStatus(403);
    }

    @Test
    void callbackWithNoSavedStateReturns403() throws Exception
    {
        when(this.httpRequest.getParameter("code")).thenReturn(AUTH_CODE);
        when(this.httpRequest.getParameter("state")).thenReturn("some-state");
        when(this.httpSession.getAttribute("zhixi_oauth2_state")).thenReturn(null);
        when(this.httpResponse.getWriter()).thenReturn(this.printWriter);

        this.action.doGet(this.httpRequest, this.httpResponse);

        verify(this.httpResponse).setStatus(403);
    }

    // ── User lookup ──

    @Test
    void callbackUserNotFoundReturns403() throws Exception
    {
        when(this.httpRequest.getParameter("code")).thenReturn(AUTH_CODE);
        when(this.httpRequest.getParameter("state")).thenReturn(STATE);
        when(this.httpSession.getAttribute("zhixi_oauth2_state")).thenReturn(STATE);
        when(this.httpResponse.getWriter()).thenReturn(this.printWriter);

        doReturn(ACCESS_TOKEN).when(this.action).exchangeToken(eq(AUTH_CODE), any());
        doReturn(createUserInfo(USER_LOGIN_ID))
            .when(this.action).fetchUserInfo(eq(ACCESS_TOKEN), any());

        // User does NOT exist
        DocumentReference userRef = new DocumentReference("xwiki", "XWiki", USER_LOGIN_ID);
        doReturn(false).when(this.oldcore.getSpyXWiki()).exists(eq(userRef), any());
        doReturn(java.util.Collections.emptyList())
            .when(this.oldcore.getSpyXWiki()).search(anyString(), any(), any());

        this.action.doGet(this.httpRequest, this.httpResponse);

        verify(this.httpResponse).setStatus(403);
    }

    @Test
    void callbackSuccessRedirectsToHome() throws Exception
    {
        when(this.httpRequest.getParameter("code")).thenReturn(AUTH_CODE);
        when(this.httpRequest.getParameter("state")).thenReturn(STATE);
        when(this.httpSession.getAttribute("zhixi_oauth2_state")).thenReturn(STATE);

        doReturn(ACCESS_TOKEN).when(this.action).exchangeToken(eq(AUTH_CODE), any());
        doReturn(createUserInfo(USER_LOGIN_ID))
            .when(this.action).fetchUserInfo(eq(ACCESS_TOKEN), any());

        // User exists
        DocumentReference userRef = new DocumentReference("xwiki", "XWiki", USER_LOGIN_ID);
        doReturn(true).when(this.oldcore.getSpyXWiki()).exists(eq(userRef), any());

        // Mock loginAndRedirect to avoid Jakarta→javax bridge complexity in tests
        doNothing().when(this.action).loginAndRedirect(anyString(), any(), any(), any());

        this.action.doGet(this.httpRequest, this.httpResponse);

        // Verify loginAndRedirect was called with the expected user
        verify(this.action).loginAndRedirect(eq("XWiki." + USER_LOGIN_ID), any(), any(), any());
        verify(this.httpSession).removeAttribute("zhixi_oauth2_state");
    }

    @Test
    void callbackTokenExchangeFailureReturns403() throws Exception
    {
        when(this.httpRequest.getParameter("code")).thenReturn(AUTH_CODE);
        when(this.httpRequest.getParameter("state")).thenReturn(STATE);
        when(this.httpSession.getAttribute("zhixi_oauth2_state")).thenReturn(STATE);
        when(this.httpResponse.getWriter()).thenReturn(this.printWriter);

        doReturn(null).when(this.action).exchangeToken(eq(AUTH_CODE), any());

        this.action.doGet(this.httpRequest, this.httpResponse);

        verify(this.httpResponse).setStatus(403);
    }

    // ── JSON deserialization resilience ──

    @Test
    void userInfoDeserializationIgnoresUnknownFields() throws Exception
    {
        // DOAP user_info may return more fields than the DTO declares (e.g. birthday).
        // Unknown fields must be ignored instead of failing the whole login.
        String json = "{"
            + "\"username\":\"admin\","
            + "\"name\":\"Admin User\","
            + "\"email\":\"admin@example.com\","
            + "\"enabled\":true,"
            + "\"birthday\":\"1990-01-01\","
            + "\"phone\":\"13800000000\","
            + "\"authorities\":[{\"authority\":\"ROLE_USER\",\"description\":\"普通用户\",\"extra\":1}]"
            + "}";

        ZhixiOAuth2LoginAction.ZhixiUserInfo userInfo =
            new ObjectMapper().readValue(json, ZhixiOAuth2LoginAction.ZhixiUserInfo.class);

        assertEquals("admin", userInfo.getUsername());
    }

    @Test
    void tokenResponseDeserializationIgnoresUnknownFields() throws Exception
    {
        String json = "{"
            + "\"access_token\":\"abc123\","
            + "\"token_type\":\"bearer\","
            + "\"expires_in\":3600,"
            + "\"refresh_token\":\"not-used\","
            + "\"extra_field\":\"ignored\""
            + "}";

        ZhixiOAuth2LoginAction.ZhixiTokenResponse response =
            new ObjectMapper().readValue(json, ZhixiOAuth2LoginAction.ZhixiTokenResponse.class);

        assertEquals("abc123", response.getAccessToken());
        assertEquals(3600, response.getExpiresIn());
    }

    // ── Helpers ──

    private ZhixiOAuth2LoginAction.ZhixiUserInfo createUserInfo(String username)
    {
        ZhixiOAuth2LoginAction.ZhixiUserInfo userInfo =
            new ZhixiOAuth2LoginAction.ZhixiUserInfo();
        userInfo.setUsername(username);
        return userInfo;
    }
}
