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

import org.xwiki.component.annotation.Role;

/**
 * Records login/logout audit events. Implementations must never throw: audit logging must not break the login or
 * logout flow.
 *
 * @version $Id$
 * @since 18.1.0
 */
@Role
public interface LoginAuditService
{
    /**
     * Records an audit event.
     *
     * @param eventType the event type
     * @param channel the login channel; {@code null} for events not tied to a channel (e.g. {@code LOGOUT})
     * @param userName the user name (fully qualified like {@code wiki:XWiki.admin} when known, otherwise the raw
     *            submitted name); may be {@code null}
     * @param ip the client IP address; may be {@code null} or empty
     * @param detail optional detail (e.g. failure reason); may be {@code null} or empty
     */
    void record(LoginEventType eventType, LoginChannel channel, String userName, String ip, String detail);
}
