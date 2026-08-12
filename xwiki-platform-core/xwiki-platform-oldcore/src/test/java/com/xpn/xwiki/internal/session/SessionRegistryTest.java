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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionBindingEvent;
import javax.servlet.http.HttpSessionBindingListener;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SessionRegistry}.
 *
 * @version $Id$
 */
class SessionRegistryTest
{
    private static final String USER = "wiki:XWiki.admin";

    private HttpSession mockSession(String id)
    {
        HttpSession session = mock(HttpSession.class);
        when(session.getId()).thenReturn(id);
        // Simulate real session attribute storage.
        Map<String, Object> attributes = new HashMap<>();
        doAnswer(invocation -> {
            attributes.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(session).setAttribute(anyString(), any());
        when(session.getAttribute(anyString()))
            .thenAnswer(invocation -> attributes.get(invocation.getArgument(0)));
        when(session.getAttributeNames()).thenAnswer(invocation -> {
            List<String> names = new ArrayList<>(attributes.keySet());
            return Collections.enumeration(names);
        });
        return session;
    }

    @Test
    void registerMakesSessionActiveForUser()
    {
        SessionRegistry registry = new SessionRegistry();
        HttpSession session = mockSession("session-1");

        registry.register(USER, session);

        assertSame(session, registry.getSession(USER));
        assertTrue(registry.hasActiveSession(USER));
    }

    @Test
    void registerKicksPreviousSessionOfSameUser()
    {
        SessionRegistry registry = new SessionRegistry();
        HttpSession firstSession = mockSession("session-1");
        HttpSession secondSession = mockSession("session-2");

        registry.register(USER, firstSession);
        registry.register(USER, secondSession);

        // The first session must have been invalidated (multi-point login / single-session enforcement).
        verify(firstSession).invalidate();
        assertSame(secondSession, registry.getSession(USER));
    }

    @Test
    void registeringSameSessionTwiceDoesNotInvalidateIt()
    {
        SessionRegistry registry = new SessionRegistry();
        HttpSession session = mockSession("session-1");

        registry.register(USER, session);
        registry.register(USER, session);

        verify(session, never()).invalidate();
        assertSame(session, registry.getSession(USER));
    }

    @Test
    void sessionDestructionCleansUpMapping()
    {
        SessionRegistry registry = new SessionRegistry();
        HttpSession session = mockSession("session-1");
        registry.register(USER, session);

        fireSessionDestroyed(registry, session);

        assertFalse(registry.hasActiveSession(USER));
        assertNull(registry.getSession(USER));
    }

    @Test
    void staleSessionDestructionDoesNotRemoveNewerSession()
    {
        SessionRegistry registry = new SessionRegistry();
        HttpSession firstSession = mockSession("session-1");
        HttpSession secondSession = mockSession("session-2");

        registry.register(USER, firstSession);
        registry.register(USER, secondSession);
        // Simulate the cascade: invalidating the first session fires its binding listener.
        fireSessionDestroyed(registry, firstSession);

        // The newer session must remain active.
        assertSame(secondSession, registry.getSession(USER));
    }

    @Test
    void unregisterWithDifferentSessionIsNoOp()
    {
        SessionRegistry registry = new SessionRegistry();
        HttpSession session = mockSession("session-1");
        HttpSession otherSession = mockSession("session-2");
        registry.register(USER, session);

        registry.unregister(USER, otherSession);

        assertSame(session, registry.getSession(USER));
    }

    @Test
    void getSessionLazilyRemovesInvalidatedSession()
    {
        SessionRegistry registry = new SessionRegistry();
        HttpSession session = mockSession("session-1");
        registry.register(USER, session);

        // Simulate the session being destroyed without its cleanup listener being called.
        when(session.getAttributeNames()).thenThrow(new IllegalStateException("session invalid"));

        assertNull(registry.getSession(USER));
        assertFalse(registry.hasActiveSession(USER));

        // The stale entry must have been removed: registering a new session must not see the old one.
        HttpSession newSession = mockSession("session-2");
        registry.register(USER, newSession);
        assertSame(newSession, registry.getSession(USER));
        verify(session, never()).invalidate();
    }

    @Test
    void registerSkipsAlreadyInvalidPreviousSession()
    {
        SessionRegistry registry = new SessionRegistry();
        HttpSession firstSession = mockSession("session-1");
        registry.register(USER, firstSession);

        // The previous session becomes invalid (e.g. the user logged out from another tab).
        when(firstSession.getAttributeNames()).thenThrow(new IllegalStateException("session invalid"));

        // Registering a new session must not throw and must not touch the stale session.
        HttpSession secondSession = mockSession("session-2");
        registry.register(USER, secondSession);

        assertSame(secondSession, registry.getSession(USER));
        verify(firstSession, never()).invalidate();
    }

    private void fireSessionDestroyed(SessionRegistry registry, HttpSession session)
    {
        // Fire the binding listener valueUnbound, exactly like the servlet container does when the session is
        // invalidated or expires.
        for (HttpSessionBindingListener binding : findBindings(session)) {
            binding.valueUnbound(mock(HttpSessionBindingEvent.class));
        }
    }

    private List<HttpSessionBindingListener> findBindings(HttpSession session)
    {
        List<HttpSessionBindingListener> bindings = new ArrayList<>();
        Enumeration<String> names = session.getAttributeNames();
        while (names.hasMoreElements()) {
            Object value = session.getAttribute(names.nextElement());
            if (value instanceof HttpSessionBindingListener) {
                bindings.add((HttpSessionBindingListener) value);
            }
        }
        return bindings;
    }
}
