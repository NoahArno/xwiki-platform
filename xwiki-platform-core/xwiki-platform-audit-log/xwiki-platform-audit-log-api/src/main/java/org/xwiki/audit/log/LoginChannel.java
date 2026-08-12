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
package org.xwiki.audit.log;

/**
 * Login channels (entry points) tracked by the audit log.
 *
 * @version $Id$
 * @since 18.1.0
 */
public enum LoginChannel
{
    /**
     * OA SSO redirect login ({@code /xwiki/oa-login}).
     */
    OA("OA"),

    /**
     * Zhixi (知悉) OAuth2 login ({@code /xwiki/zhixi-login}).
     */
    ZHIXI("知悉"),

    /**
     * XWiki form login (including remember-me cookie auto re-login).
     */
    FORM("表单"),

    /**
     * HTTP Basic authentication.
     */
    BASIC("基本认证");

    private final String displayName;

    LoginChannel(String displayName)
    {
        this.displayName = displayName;
    }

    /**
     * @return the Chinese display name used by the audit log page
     */
    public String getDisplayName()
    {
        return this.displayName;
    }
}
