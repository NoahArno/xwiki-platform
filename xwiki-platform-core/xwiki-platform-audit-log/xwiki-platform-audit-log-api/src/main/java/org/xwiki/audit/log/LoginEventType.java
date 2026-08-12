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
 * Audit log event types.
 *
 * @version $Id$
 * @since 18.1.0
 */
public enum LoginEventType
{
    /**
     * A user successfully authenticated.
     */
    LOGIN_SUCCESS("登录成功"),

    /**
     * An authentication attempt failed.
     */
    LOGIN_FAILURE("登录失败"),

    /**
     * The user explicitly logged out via {@code /bin/logout}.
     */
    LOGOUT("登出");

    private final String displayName;

    LoginEventType(String displayName)
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
