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

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.net.URIBuilder;
import org.securityfilter.filter.SecurityRequestWrapper;
import org.securityfilter.realm.SimplePrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.model.reference.DocumentReference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.internal.user.UserAuthenticatedEventNotifier;

/**
 * Servlet for handling Zhixi (知悉) OAuth2 single sign-on via the DOAP authentication center.
 * <p>
 * Implements the OAuth2 Authorization Code flow:
 * <ol>
 *   <li>User visits {@code /xwiki/zhixi-login} → redirected to DOAP authorize endpoint</li>
 *   <li>User authenticates on DOAP → redirected back to {@code /xwiki/zhixi-login?code=...&state=...}</li>
 *   <li>Servlet exchanges the authorization code for an access token via DOAP token endpoint</li>
 *   <li>Servlet fetches user info via DOAP user_info endpoint</li>
 *   <li>If the user exists in XWiki, they are logged in automatically (disabled XWiki users are rejected by the standard per-request auth check)</li>
 * </ol>
 * <p>
 * This is a standalone servlet (not an XWiki Action) to bypass the XWiki authentication
 * framework which would otherwise redirect unauthenticated requests to the login page.
 * <p>
 * Configuration in {@code xwiki.cfg}:
 * <pre>
 * xwiki.authentication.zhixi.doap-host=http://sit-doap.mis.bcs
 * xwiki.authentication.zhixi.client-id=&lt;client_id&gt;
 * xwiki.authentication.zhixi.client-secret=&lt;client_secret&gt;
 * xwiki.authentication.zhixi.redirect-uri=http://xwiki.company.com/xwiki/zhixi-login
 * </pre>
 *
 * @version $Id$
 */
public class ZhixiOAuth2LoginAction extends HttpServlet
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ZhixiOAuth2LoginAction.class);

    private static final String SESSION_STATE_KEY = "zhixi_oauth2_state";

    private static final String AUTHZ_ENDPOINT = "/api/sso/oauth/authorize";

    private static final String TOKEN_ENDPOINT = "/api/sso/oauth/token";

    private static final String USERINFO_ENDPOINT = "/api/sso/oauth/user_info";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException
    {
        String code = request.getParameter("code");
        String state = request.getParameter("state");

        if (StringUtils.isBlank(code)) {
            // Step 1-2: Start the OAuth2 authorization flow
            startAuthorization(request, response);
        } else {
            // Step 6-12: Handle the OAuth2 callback
            handleCallback(code, state, request, response);
        }
    }

    /**
     * Initiates the OAuth2 Authorization Code flow by redirecting the user's browser
     * to the DOAP authorization endpoint.
     */
    private void startAuthorization(HttpServletRequest request, HttpServletResponse response)
        throws IOException
    {
        XWikiContext context = null;
        try {
            context = initializeXWikiContext(request, response);

            // Read configuration
            String doapHost = context.getWiki().Param("xwiki.authentication.zhixi.doap-host");
            String clientId = context.getWiki().Param("xwiki.authentication.zhixi.client-id");
            String redirectUri = context.getWiki().Param("xwiki.authentication.zhixi.redirect-uri");

            if (StringUtils.isAnyBlank(doapHost, clientId)) {
                LOGGER.error("Zhixi OAuth2: missing required configuration. "
                    + "doap-host=[{}], client-id=[{}]", doapHost, clientId);
                writeError(response, "系统配置错误：知悉 OAuth2 未配置");
                return;
            }

            // Generate a random state for CSRF protection
            byte[] stateBytes = new byte[32];
            SECURE_RANDOM.nextBytes(stateBytes);
            String state = Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes);

            // Store state in session for callback validation
            HttpSession session = request.getSession(true);
            session.setAttribute(SESSION_STATE_KEY, state);

            // Build the DOAP authorization URL
            URI authzUri = new URIBuilder(doapHost + AUTHZ_ENDPOINT)
                .addParameter("client_id", clientId)
                .addParameter("response_type", "code")
                .addParameter("redirect_uri",
                    StringUtils.isNotBlank(redirectUri) ? redirectUri : request.getRequestURL().toString())
                .addParameter("state", state)
                .build();

            LOGGER.info("Zhixi OAuth2: redirecting to DOAP authorize endpoint [{}]", authzUri);
            response.sendRedirect(authzUri.toString());

        } catch (URISyntaxException e) {
            LOGGER.error("Zhixi OAuth2: invalid DOAP authorization URI", e);
            writeError(response, "系统配置错误：DOAP 地址无效");
        } catch (XWikiException e) {
            LOGGER.error("Zhixi OAuth2: failed to initialize XWiki context for authorization", e);
            writeError(response, "系统内部错误");
        } finally {
            if (context != null) {
                context.getWiki().getStore().cleanUp(context);
            }
        }
    }

    /**
     * Handles the OAuth2 callback from DOAP:
     * validates state, exchanges code for token, fetches user info, and logs the user in.
     */
    private void handleCallback(String code, String state,
        HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        XWikiContext context = null;
        try {
            context = initializeXWikiContext(request, response);

            // ① Validate CSRF state
            HttpSession session = request.getSession(false);
            String savedState = (session != null) ? (String) session.getAttribute(SESSION_STATE_KEY) : null;

            if (savedState == null || !savedState.equals(state)) {
                LOGGER.warn("Zhixi OAuth2: state mismatch. saved=[{}], received=[{}]", savedState, state);
                writeError(response, "CSRF 校验失败");
                return;
            }
            // Clear the state after successful validation (one-time use)
            session.removeAttribute(SESSION_STATE_KEY);

            // ② Exchange authorization code for access token
            String accessToken = exchangeToken(code, context);
            if (accessToken == null) {
                writeError(response, "获取访问令牌失败");
                return;
            }

            // ③ Fetch user info from DOAP
            ZhixiUserInfo userInfo = fetchUserInfo(accessToken, context);
            if (userInfo == null) {
                writeError(response, "获取用户信息失败");
                return;
            }

            // ⑤ Find the user in XWiki
            String user = findUserByLoginId(userInfo.getUsername(), context);
            if (user == null) {
                LOGGER.warn("Zhixi OAuth2: user [{}] not found in XWiki", userInfo.getUsername());
                writeError(response, "当前用户 " + userInfo.getUsername() + " 不存在");
                return;
            }

            LOGGER.info("Zhixi OAuth2: user [{}] authenticated successfully via DOAP", user);

            // ⑥ Set login state and redirect to home page
            loginAndRedirect(user, context, request, response);

        } catch (XWikiException e) {
            LOGGER.error("Zhixi OAuth2: failed to process callback", e);
            writeError(response, "系统内部错误");
        } finally {
            if (context != null) {
                context.getWiki().getStore().cleanUp(context);
            }
        }
    }

    /**
     * Exchanges the OAuth2 authorization code for an access token.
     * POSTs to the DOAP token endpoint with query-string parameters as specified in the DOAP API doc.
     *
     * @param code    the authorization code from the callback
     * @param context the XWiki context
     * @return the access token string, or {@code null} on failure
     */
    String exchangeToken(String code, XWikiContext context)
    {
        String doapHost = context.getWiki().Param("xwiki.authentication.zhixi.doap-host");
        String clientId = context.getWiki().Param("xwiki.authentication.zhixi.client-id");
        String clientSecret = context.getWiki().Param("xwiki.authentication.zhixi.client-secret");
        String redirectUri = context.getWiki().Param("xwiki.authentication.zhixi.redirect-uri");

        if (StringUtils.isAnyBlank(doapHost, clientId, clientSecret)) {
            LOGGER.error("Zhixi OAuth2: missing config for token exchange");
            return null;
        }

        try {
            // Build the token endpoint URL with query-string parameters (per DOAP API spec)
            URI tokenUri = new URIBuilder(doapHost + TOKEN_ENDPOINT)
                .addParameter("client_id", clientId)
                .addParameter("client_secret", clientSecret)
                .addParameter("code", code)
                .addParameter("grant_type", "authorization_code")
                .addParameter("redirect_uri",
                    StringUtils.isNotBlank(redirectUri) ? redirectUri : "")
                .build();

            HttpPost httpPost = new HttpPost(tokenUri);

            try (CloseableHttpClient httpClient = HttpClients.createSystem();
                 CloseableHttpResponse response = httpClient.execute(httpPost)) {

                int statusCode = response.getCode();
                String responseBody = EntityUtils.toString(response.getEntity());

                if (statusCode != 200) {
                    LOGGER.error("Zhixi OAuth2: token exchange failed. status=[{}], body=[{}]",
                        statusCode, responseBody);
                    return null;
                }

                ZhixiTokenResponse tokenResponse =
                    OBJECT_MAPPER.readValue(responseBody, ZhixiTokenResponse.class);

                if (StringUtils.isBlank(tokenResponse.getAccessToken())) {
                    LOGGER.error("Zhixi OAuth2: token response missing access_token. body=[{}]", responseBody);
                    return null;
                }

                LOGGER.info("Zhixi OAuth2: token exchange successful. expires_in=[{}]",
                    tokenResponse.getExpiresIn());
                return tokenResponse.getAccessToken();

            }
        } catch (URISyntaxException | org.apache.hc.core5.http.ParseException e) {
            LOGGER.error("Zhixi OAuth2: invalid token endpoint URI", e);
            return null;
        } catch (IOException e) {
            LOGGER.error("Zhixi OAuth2: token exchange IO error", e);
            return null;
        }
    }

    /**
     * Fetches user information from the DOAP user_info endpoint using the access token.
     *
     * @param accessToken the OAuth2 access token
     * @param context     the XWiki context
     * @return the user info, or {@code null} on failure
     */
    ZhixiUserInfo fetchUserInfo(String accessToken, XWikiContext context)
    {
        String doapHost = context.getWiki().Param("xwiki.authentication.zhixi.doap-host");

        if (StringUtils.isBlank(doapHost)) {
            LOGGER.error("Zhixi OAuth2: doap-host not configured");
            return null;
        }

        try {
            URI userinfoUri = new URIBuilder(doapHost + USERINFO_ENDPOINT).build();
            HttpGet httpGet = new HttpGet(userinfoUri);
            httpGet.setHeader("Authorization", "Bearer " + accessToken);

            try (CloseableHttpClient httpClient = HttpClients.createSystem();
                 CloseableHttpResponse response = httpClient.execute(httpGet)) {

                int statusCode = response.getCode();
                String responseBody = EntityUtils.toString(response.getEntity());

                if (statusCode != 200) {
                    LOGGER.error("Zhixi OAuth2: user_info request failed. status=[{}], body=[{}]",
                        statusCode, responseBody);
                    return null;
                }

                ZhixiUserInfo userInfo = OBJECT_MAPPER.readValue(responseBody, ZhixiUserInfo.class);

                if (StringUtils.isBlank(userInfo.getUsername())) {
                    LOGGER.error("Zhixi OAuth2: user_info response missing username. body=[{}]", responseBody);
                    return null;
                }

                LOGGER.info("Zhixi OAuth2: user_info fetched. username=[{}]",
                    userInfo.getUsername());
                return userInfo;

            }
        } catch (URISyntaxException | org.apache.hc.core5.http.ParseException e) {
            LOGGER.error("Zhixi OAuth2: invalid user_info endpoint URI or parse error", e);
            return null;
        } catch (IOException e) {
            LOGGER.error("Zhixi OAuth2: user_info request IO error", e);
            return null;
        }
    }

    /**
     * Sets the user as authenticated and redirects to the XWiki home page.
     * Uses the same mechanism as {@link OALoginAction}: sets a SimplePrincipal
     * on a SecurityRequestWrapper and stores it in the HTTP session.
     */
    void loginAndRedirect(String user, XWikiContext context,
        HttpServletRequest request, HttpServletResponse response) throws XWikiException, IOException
    {
        String principalName = context.getWikiId() + ":" + user;
        SimplePrincipal principal = new SimplePrincipal(principalName);

        javax.servlet.http.HttpServletRequest javaxRequest =
            org.xwiki.jakartabridge.servlet.JakartaServletBridge.toJavax(request);
        SecurityRequestWrapper wrappedRequest =
            new SecurityRequestWrapper(javaxRequest, null, null, "FORM");
        wrappedRequest.setUserPrincipal(principal);

        // Store principal in HTTP session so SecurityFilter picks it up on the next request
        HttpSession session = request.getSession(true);
        session.setAttribute("org.securityfilter.filter.SecurityFilter.PRINCIPAL", principal);

        // Notify the authentication success event
        UserAuthenticatedEventNotifier notifier =
            Utils.getComponent(UserAuthenticatedEventNotifier.class);
        notifier.notify(principalName);

        // Redirect to the wiki home page
        String redirectUrl = context.getURLFactory().createURL(
            context.getWiki().getDefaultSpace(context),
            context.getWiki().getDefaultPage(context), "view", context
        ).toString();
        response.sendRedirect(response.encodeRedirectURL(redirectUrl));
    }

    /**
     * Initialize a minimal XWiki context for this servlet.
     * Uses the same pattern as {@code OALoginAction} and {@code XWikiContextInitializationFilter}.
     */
    XWikiContext initializeXWikiContext(HttpServletRequest request, HttpServletResponse response)
        throws XWikiException
    {
        javax.servlet.ServletContext javaxServletContext =
            org.xwiki.jakartabridge.servlet.JakartaServletBridge.toJavax(request.getServletContext());

        XWikiServletContext xwikiEngine = new XWikiServletContext(javaxServletContext);
        XWikiServletRequest xwikiRequest = new XWikiServletRequest(
            org.xwiki.jakartabridge.servlet.JakartaServletBridge.toJavax(request));
        XWikiServletResponse xwikiResponse = new XWikiServletResponse(
            org.xwiki.jakartabridge.servlet.JakartaServletBridge.toJavax(response));

        XWikiContext context = Utils.prepareContext("", xwikiRequest, xwikiResponse, xwikiEngine);

        // Initialize the Container component which sets up the Execution context.
        try {
            org.xwiki.container.servlet.ServletContainerInitializer containerInitializer =
                Utils.getComponent(org.xwiki.container.servlet.ServletContainerInitializer.class);
            containerInitializer.initializeRequest(
                org.xwiki.jakartabridge.servlet.JakartaServletBridge.toJavax(request), context);
            containerInitializer.initializeResponse(
                org.xwiki.jakartabridge.servlet.JakartaServletBridge.toJavax(response));
            containerInitializer.initializeSession(
                org.xwiki.jakartabridge.servlet.JakartaServletBridge.toJavax(request));
        } catch (org.xwiki.container.servlet.ServletContainerException e) {
            throw new XWikiException(XWikiException.MODULE_XWIKI, XWikiException.ERROR_XWIKI_UNKNOWN,
                "Failed to initialize Container component", e);
        }

        XWiki.getXWiki(context);
        context.setURLFactory(
            context.getWiki().getURLFactoryService().createURLFactory(context.getMode(), context));
        context.getWiki().prepareResources(context);

        return context;
    }

    /**
     * Find a user in XWiki by their login ID (employee ID / 工号).
     * Uses the same lookup strategy as {@code XWikiAuthServiceImpl.findUser()}.
     *
     * @param username the user's login ID (corresponds to DOAP {@code username} field)
     * @param context  the XWiki context
     * @return the full user name (e.g. "XWiki.admin"), or {@code null} if not found
     * @throws XWikiException on lookup error
     */
    private String findUserByLoginId(String username, XWikiContext context) throws XWikiException
    {
        // First, check if the user document exists directly
        DocumentReference userRef = new DocumentReference(context.getWikiId(), "XWiki", username);
        if (context.getWiki().exists(userRef, context)) {
            return "XWiki." + username;
        }

        // Fallback: HQL query for case-insensitive database compatibility (e.g. MySQL)
        String sql = "select distinct doc.fullName from XWikiDocument as doc";
        Object[][] whereParams = new Object[][] {
            { "doc.space", "XWiki" },
            { "doc.name", username }
        };
        List<String> list = context.getWiki().search(sql, whereParams, context);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * Writes an HTML error response.
     */
    private void writeError(HttpServletResponse response, String message) throws IOException
    {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("text/html; charset=UTF-8");
        PrintWriter writer = response.getWriter();
        writer.println("<!DOCTYPE html>");
        writer.println("<html lang=\"zh-CN\">");
        writer.println("<head><meta charset=\"UTF-8\"><title>知悉登录</title></head>");
        writer.println("<body>");
        writer.println("<h1>知悉登录失败</h1>");
        writer.println("<p>" + org.apache.commons.text.StringEscapeUtils.escapeHtml4(message) + "</p>");
        writer.println("</body>");
        writer.println("</html>");
        writer.flush();
    }

    // ── Inner classes for JSON deserialization ──

    /**
     * DOAP token endpoint response (snake_case JSON keys).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
public static class ZhixiTokenResponse
    {
        @JsonProperty("access_token")
        private String accessToken;

        @JsonProperty("token_type")
        private String tokenType;

        @JsonProperty("expires_in")
        private int expiresIn;

        private String scope;
        private String jti;

        public String getAccessToken()
        {
            return accessToken;
        }

        public void setAccessToken(String accessToken)
        {
            this.accessToken = accessToken;
        }

        public String getTokenType()
        {
            return tokenType;
        }

        public void setTokenType(String tokenType)
        {
            this.tokenType = tokenType;
        }

        public int getExpiresIn()
        {
            return expiresIn;
        }

        public void setExpiresIn(int expiresIn)
        {
            this.expiresIn = expiresIn;
        }

        public String getScope()
        {
            return scope;
        }

        public void setScope(String scope)
        {
            this.scope = scope;
        }

        public String getJti()
        {
            return jti;
        }

        public void setJti(String jti)
        {
            this.jti = jti;
        }
    }

    /**
     * DOAP user_info endpoint response.
     * <p>
     * Only {@code username} is used at login time. Any other fields returned by DOAP
     * (e.g. enabled, birthday, phone, name, authorities) are ignored via {@link JsonIgnoreProperties}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
public static class ZhixiUserInfo
    {
        private String username;

        public String getUsername()
        {
            return username;
        }

        public void setUsername(String username)
        {
            this.username = username;
        }

        @Override
        public String toString()
        {
            return "ZhixiUserInfo{"
                + "username='" + username + '\''
                + '}';
        }
    }
}
