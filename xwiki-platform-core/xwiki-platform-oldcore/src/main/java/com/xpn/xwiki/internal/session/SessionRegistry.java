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
 * 02110-1301 USA, or see the XWiki site for details: http://www.xwiki.org
 */
package com.xpn.xwiki.internal.session;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.inject.Singleton;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;

import org.xwiki.component.annotation.Component;

/**
 * Tracks the HTTP session associated with each authenticated user so that a new login can invalidate the previous
 * session of the same user (single-session / "kick previous session" enforcement).
 * <p>
 * The mapping is automatically cleaned up when the session is destroyed (logout, idle timeout or explicit
 * invalidation) thanks to an {@link HttpSessionBindingListener} attached to the session.
 *
 * @version $Id$
 * @since 18.1.0
 */
@Component(roles = SessionRegistry.class)
@Singleton
public class SessionRegistry
{
    /**
     * Prefix of the session attribute used to attach the cleanup listener. The full attribute name includes the user
     * id so that a single session can track several users (e.g. when switching account in the same browser).
     */
    private static final String BINDING_ATTRIBUTE_PREFIX = SessionRegistry.class.getName() + ".binding.";

    private final ConcurrentMap<String, HttpSession> sessionsByUser = new ConcurrentHashMap<>();

    /**
     * Registers the given session as the active session of the given user and invalidates the previous active session
     * of the same user, if any.
     *
     * @param userId the fully qualified user identifier (e.g. {@code wiki:XWiki.admin})
     * @param session the session to register
     */
    public void register(String userId, HttpSession session)
    {
        // Attach the cleanup listener (once per user/session) so the mapping is removed when the session is destroyed
        // (logout, idle timeout, or invalidation triggered by a new login of the same user).
        String bindingKey = BINDING_ATTRIBUTE_PREFIX + userId;
        if (session.getAttribute(bindingKey) == null) {
            session.setAttribute(bindingKey, new SessionBindingListener(userId, session));
        }

        // Kick the previous session of the same user, if any. Note that the previous session may already have been
        // invalidated (e.g. the user logged out from another tab), so we only touch it if it is still valid.
        HttpSession previousSession = this.sessionsByUser.put(userId, session);
        if (previousSession != null && previousSession != session && isSessionValid(previousSession)
            && !previousSession.getId().equals(session.getId())) {
            try {
                previousSession.invalidate();
            } catch (IllegalStateException e) {
                // The previous session was invalidated concurrently: nothing to do.
            }
        }
    }

    /**
     * Removes the user/session mapping unless it has already been replaced by a more recent session of the same user.
     *
     * @param userId the fully qualified user identifier
     * @param session the session to remove
     */
    public void unregister(String userId, HttpSession session)
    {
        this.sessionsByUser.compute(userId, (key, current) -> current == session ? null : current);
    }

    /**
     * @param userId the fully qualified user identifier
     * @return the active session of the given user, or {@code null} if the user has no registered session
     */
    public HttpSession getSession(String userId)
    {
        HttpSession session = this.sessionsByUser.get(userId);
        if (session != null && !isSessionValid(session)) {
            // The session was destroyed without its cleanup listener being called (should not happen with Tomcat,
            // but guard against it to avoid leaking stale entries). Remove it atomically: only if it is still the
            // session mapped for this user.
            this.sessionsByUser.remove(userId, session);
            return null;
        }
        return session;
    }

    /**
     * @param userId the fully qualified user identifier
     * @return {@code true} if the user has a registered active session
     */
    public boolean hasActiveSession(String userId)
    {
        return getSession(userId) != null;
    }

    /**
     * @param session the session to check
     * @return {@code true} if the given session is not {@code null} and has not been invalidated
     */
    private boolean isSessionValid(HttpSession session)
    {
        try {
            session.getAttributeNames();
            return true;
        } catch (IllegalStateException e) {
            // The session has been invalidated.
            return false;
        }
    }

    /**
     * Listener attached to each registered session in order to clean up the registry when the session is destroyed.
     */
    private class SessionBindingListener implements HttpSessionBindingListener
    {
        private final String userId;

        private final HttpSession session;

        SessionBindingListener(String userId, HttpSession session)
        {
            this.userId = userId;
            this.session = session;
        }

        @Override
        public void valueBound(HttpSessionBindingEvent event)
        {
            // Nothing to do.
        }

        @Override
        public void valueUnbound(HttpSessionBindingEvent event)
        {
            SessionRegistry.this.unregister(this.userId, this.session);
        }
    }
}
