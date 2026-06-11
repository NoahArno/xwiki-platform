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
package org.xwiki.rendering.async.internal;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.bridge.event.DocumentCreatedEvent;
import org.xwiki.bridge.event.DocumentDeletedEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.observation.AbstractEventListener;
import org.xwiki.observation.event.Event;

import com.xpn.xwiki.doc.XWikiDocument;

/**
 * Invalidates navigation panel async cache entries when documents are created or deleted.
 * Unlike {@link AsyncRendererCacheListener}, this listener only responds to structural changes
 * (create/delete), not content updates. This way, editing page content does not clear the
 * cached navigation tree — only creating or deleting pages does.
 *
 * @version $Id$
 * @since 18.1.0
 */
@Component
@Singleton
@Named(NavigationCacheInvalidator.NAME)
public class NavigationCacheInvalidator extends AbstractEventListener
{
    /**
     * The name of the listener.
     */
    public static final String NAME = "org.xwiki.rendering.async.internal.NavigationCacheInvalidator";

    /**
     * The use type string used in Velocity: {@code $services.async.use('navigation_space', $spaceKey)}.
     */
    public static final String USE_TYPE = "navigation_space";

    /**
     * Special key for the global (non-space-scoped) navigation cache.
     */
    public static final String GLOBAL_KEY = "__global__";

    @Inject
    private AsyncRendererCache cache;

    /**
     * Default constructor. Listens only for document creation and deletion,
     * NOT for document updates (content edits).
     */
    public NavigationCacheInvalidator()
    {
        super(NAME, new DocumentCreatedEvent(), new DocumentDeletedEvent());
    }

    @Override
    public void onEvent(Event event, Object source, Object data)
    {
        XWikiDocument document = (XWikiDocument) source;

        // Invalidate the navigation cache for every space level of the affected document.
        // For example, if the document is wiki:Parent.Child.Page, we invalidate both
        // "Parent" and "Child" space caches, because both spaces' navigation trees may
        // show this document.
        for (SpaceReference spaceRef : document.getDocumentReference().getSpaceReferences()) {
            this.cache.cleanCacheByUse(USE_TYPE, spaceRef.getName());
        }

        // Also invalidate the global navigation cache (home page showing all spaces).
        this.cache.cleanCacheByUse(USE_TYPE, GLOBAL_KEY);
    }
}
