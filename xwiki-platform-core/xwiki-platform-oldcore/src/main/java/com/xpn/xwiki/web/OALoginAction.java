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
import java.util.List;

import javax.inject.Named;
import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import org.securityfilter.filter.SecurityRequestWrapper;
import org.securityfilter.realm.SimplePrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReference;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.internal.user.UserAuthenticatedEventNotifier;

/**
 * Action for handling OA (Office Automation) single sign-on (SSO) callbacks.
 * <p>
 * OA system calls this URL with signed parameters (pid, userLoginId, timestamp, sign).
 * This action verifies the MD5 signature using a pre-shared key, looks up the user
 * in XWiki by their login ID (工号), and if the user exists, logs them in automatically.
 * <p>
 * URL format: {@code /xwiki/bin/oalogin/?pid=...&userLoginId=...&timestamp=...&sign=...}
 *
 * @version $Id$
 */
@Component
@Named("oalogin")
@Singleton
public class OALoginAction extends XWikiAction
{
    private static final Logger LOGGER = LoggerFactory.getLogger(OALoginAction.class);

    private static final String TEMPLATE = "oalogin";

    /**
     * Default constructor. No need to wait for XWiki initialization for this action.
     */
    public OALoginAction()
    {
        this.waitForXWikiInitialization = false;
    }

    @Override
    public boolean action(XWikiContext context) throws XWikiException
    {
        HttpServletRequest request = context.getRequest().getHttpServletRequest();
        HttpServletResponse response = context.getResponse();

        // ① Extract OA parameters from the request
        String pid = request.getParameter("pid");
        String userLoginId = request.getParameter("userLoginId");
        String timestamp = request.getParameter("timestamp");
        String sign = request.getParameter("sign");

        // ② Validate that all required parameters are present
        if (StringUtils.isAnyBlank(pid, userLoginId, timestamp, sign)) {
            LOGGER.warn("OA SSO: missing required parameters. pid=[{}], userLoginId=[{}], timestamp=[{}]",
                pid, userLoginId, timestamp);
            context.put("message", "oa_missing_params");
            return true;
        }

        // ③ Read the OA pre-shared key from xwiki.cfg
        String oaKey = context.getWiki().Param("xwiki.authentication.oa.key");
        if (StringUtils.isBlank(oaKey)) {
            LOGGER.error("OA SSO: xwiki.authentication.oa.key is not configured in xwiki.cfg");
            context.put("message", "oa_key_not_configured");
            return true;
        }

        // ④ Compute MD5 signature and compare with the received sign
        String computedSign = DigestUtils.md5Hex(pid + userLoginId + timestamp + oaKey);
        if (!computedSign.equalsIgnoreCase(sign)) {
            LOGGER.warn("OA SSO: signature verification failed for user [{}]. "
                + "Expected=[{}], Received=[{}]", userLoginId, computedSign, sign);
            context.put("message", "oa_sign_verification_failed");
            return true;
        }

        // ⑤ Look up the user in XWiki by their login ID (工号)
        String user = findUserByLoginId(userLoginId, context);
        if (user == null) {
            LOGGER.warn("OA SSO: user [{}] not found in XWiki", userLoginId);
            context.put("message", "当前用户 " + userLoginId + " 不存在");
            return true;
        }

        LOGGER.info("OA SSO: user [{}] authenticated successfully via OA", user);

        // ⑥ Set the login state — same mechanism as MyFormAuthenticator.processLogin()
        try {
            String principalName = context.getWikiId() + ":" + user;
            SimplePrincipal principal = new SimplePrincipal(principalName);

            // Try to set the principal on the SecurityFilter's own wrapper first
            SecurityRequestWrapper wrappedRequest = getSecurityRequestWrapper(request);
            wrappedRequest.setUserPrincipal(principal);

            // Notify the authentication success event
            UserAuthenticatedEventNotifier notifier =
                Utils.getComponent(UserAuthenticatedEventNotifier.class);
            notifier.notify(principalName);

            // ⑦ Redirect to the wiki home page
            String redirectUrl = context.getURLFactory().createURL(
                context.getWiki().getDefaultSpace(context),
                context.getWiki().getDefaultPage(context), "view", context
            ).toString();
            response.sendRedirect(response.encodeRedirectURL(redirectUrl));
            return false;
        } catch (IOException e) {
            throw new XWikiException(XWikiException.MODULE_XWIKI, XWikiException.ERROR_XWIKI_UNKNOWN,
                "Failed to redirect after OA login for user [" + user + "]", e);
        }
    }

    /**
     * Find a user in XWiki by their login ID.
     * Uses the same lookup strategy as {@code XWikiAuthServiceImpl.findUser()}.
     *
     * @param username the user's login ID (工号)
     * @param context the XWiki context
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
     * Attempt to retrieve the SecurityFilter's {@link SecurityRequestWrapper} from the request.
     * If the request is already a SecurityRequestWrapper, use it directly.
     * Otherwise, create a new one (which will still allow session persistence through the
     * SecurityFilter chain on the next redirect).
     */
    private SecurityRequestWrapper getSecurityRequestWrapper(HttpServletRequest request)
    {
        if (request instanceof SecurityRequestWrapper) {
            return (SecurityRequestWrapper) request;
        }
        return new SecurityRequestWrapper(request, null, null, "FORM");
    }

    @Override
    public String render(XWikiContext context) throws XWikiException
    {
        // Set 403 status on error, matching LoginSubmitAction behavior
        String msg = (String) context.get("message");
        if (StringUtils.isNotBlank(msg)) {
            context.getResponse().setStatus(HttpServletResponse.SC_FORBIDDEN);
        }
        return TEMPLATE;
    }
}
