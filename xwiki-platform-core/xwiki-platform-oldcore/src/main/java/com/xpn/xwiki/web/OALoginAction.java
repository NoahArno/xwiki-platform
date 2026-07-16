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
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.securityfilter.filter.SecurityRequestWrapper;
import org.securityfilter.realm.SimplePrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.model.reference.DocumentReference;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.internal.user.UserAuthenticatedEventNotifier;

/**
 * Servlet for handling OA (Office Automation) single sign-on (SSO) callbacks.
 * <p>
 * OA system calls this URL with signed parameters (pid, userLoginId, timestamp, sign).
 * This servlet verifies the MD5 signature using a pre-shared key, looks up the user
 * in XWiki by their login ID (工号), and if the user exists, logs them in automatically.
 * <p>
 * URL format: {@code /xwiki/oa-login?pid=...&userLoginId=...&timestamp=...&sign=...}
 * <p>
 * This is a standalone servlet (not an XWiki Action) to bypass the XWiki authentication
 * framework which would otherwise redirect unauthenticated requests to the login page.
 *
 * @version $Id$
 */
public class OALoginAction extends HttpServlet
{
    private static final Logger LOGGER = LoggerFactory.getLogger(OALoginAction.class);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
        throws ServletException, IOException
    {
        XWikiContext context = null;
        try {
            // Initialize XWiki context
            context = initializeXWikiContext(request, response);

            // ① Extract OA parameters
            String pid = request.getParameter("pid");
            String userLoginId = request.getParameter("userLoginId");
            String timestamp = request.getParameter("timestamp");
            String sign = request.getParameter("sign");

            // ② Validate required parameters
            if (StringUtils.isAnyBlank(pid, userLoginId, timestamp, sign)) {
                LOGGER.warn("OA SSO: missing required parameters. pid=[{}], userLoginId=[{}], timestamp=[{}]",
                    pid, userLoginId, timestamp);
                writeError(response, "缺少必填参数（pid、userLoginId、timestamp、sign）");
                return;
            }

            // ③ Read OA KEY from xwiki.cfg
            String oaKey = context.getWiki().Param("xwiki.authentication.oa.key");
            if (StringUtils.isBlank(oaKey)) {
                LOGGER.error("OA SSO: xwiki.authentication.oa.key is not configured in xwiki.cfg");
                writeError(response, "系统配置错误：OA 密钥未设置");
                return;
            }

            // ④ MD5 signature verification
            String computedSign = DigestUtils.md5Hex(pid + userLoginId + timestamp + oaKey);
            if (!computedSign.equalsIgnoreCase(sign)) {
                LOGGER.warn("OA SSO: signature verification failed for user [{}]. "
                    + "Expected=[{}], Received=[{}]", userLoginId, computedSign, sign);
                writeError(response, "签名校验失败");
                return;
            }

            // ⑤ Find user in XWiki
            String user = findUserByLoginId(userLoginId, context);
            if (user == null) {
                LOGGER.warn("OA SSO: user [{}] not found in XWiki", userLoginId);
                writeError(response, "当前用户 " + userLoginId + " 不存在");
                return;
            }

            LOGGER.info("OA SSO: user [{}] authenticated successfully via OA", user);

            // ⑥ Set login state
            String principalName = context.getWikiId() + ":" + user;
            SimplePrincipal principal = new SimplePrincipal(principalName);

            javax.servlet.http.HttpServletRequest javaxRequest =
                org.xwiki.jakartabridge.servlet.JakartaServletBridge.toJavax(request);
            SecurityRequestWrapper wrappedRequest = new SecurityRequestWrapper(javaxRequest, null, null, "FORM");
            wrappedRequest.setUserPrincipal(principal);

            // Store principal in HTTP session so SecurityFilter picks it up on next request
            HttpSession session = request.getSession(true);
            session.setAttribute("org.securityfilter.filter.SecurityFilter.PRINCIPAL", principal);

            // Notify authentication event
            UserAuthenticatedEventNotifier notifier =
                Utils.getComponent(UserAuthenticatedEventNotifier.class);
            notifier.notify(principalName);

            // ⑦ Redirect to wiki home page
            String redirectUrl = context.getURLFactory().createURL(
                context.getWiki().getDefaultSpace(context),
                context.getWiki().getDefaultPage(context), "view", context
            ).toString();
            response.sendRedirect(response.encodeRedirectURL(redirectUrl));

        } catch (XWikiException e) {
            LOGGER.error("OA SSO: failed to process login", e);
            writeError(response, "系统内部错误");
        } finally {
            if (context != null) {
                context.getWiki().getStore().cleanUp(context);
            }
        }
    }

    /**
     * Initialize a minimal XWiki context for this servlet.
     * Uses the same pattern as {@code XWikiContextInitializationFilter}.
     */
    private XWikiContext initializeXWikiContext(HttpServletRequest request, HttpServletResponse response)
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
        XWiki.getXWiki(context);
        context.setURLFactory(
            context.getWiki().getURLFactoryService().createURLFactory(context.getMode(), context));
        context.getWiki().prepareResources(context);

        return context;
    }

    /**
     * Find a user in XWiki by their login ID.
     */
    private String findUserByLoginId(String username, XWikiContext context) throws XWikiException
    {
        DocumentReference userRef = new DocumentReference(context.getWikiId(), "XWiki", username);
        if (context.getWiki().exists(userRef, context)) {
            return "XWiki." + username;
        }

        String sql = "select distinct doc.fullName from XWikiDocument as doc";
        Object[][] whereParams = new Object[][] {
            { "doc.space", "XWiki" },
            { "doc.name", username }
        };
        List<String> list = context.getWiki().search(sql, whereParams, context);
        return list.isEmpty() ? null : list.get(0);
    }

    private void writeError(HttpServletResponse response, String message) throws IOException
    {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("text/html; charset=UTF-8");
        PrintWriter writer = response.getWriter();
        writer.println("<!DOCTYPE html>");
        writer.println("<html lang=\"zh-CN\">");
        writer.println("<head><meta charset=\"UTF-8\"><title>OA 登录</title></head>");
        writer.println("<body>");
        writer.println("<h1>OA 登录失败</h1>");
        writer.println("<p>" + org.apache.commons.text.StringEscapeUtils.escapeHtml4(message) + "</p>");
        writer.println("</body>");
        writer.println("</html>");
        writer.flush();
    }
}
