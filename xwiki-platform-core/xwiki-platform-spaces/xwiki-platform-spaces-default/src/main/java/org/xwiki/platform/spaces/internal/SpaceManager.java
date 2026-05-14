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
package org.xwiki.platform.spaces.internal;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.SpaceReference;
import org.xwiki.platform.spaces.SpaceConstants;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/**
 * Manages Confluence-like spaces within an XWiki wiki.
 * A "space" is a top-level XWiki space whose WebHome has a SpaceCode.SpaceClass XObject.
 *
 * @version $Id$
 * @since 18.1.0
 */
@Component
@Singleton
public class SpaceManager
{
    @Inject
    private XWikiContext xcontext;

    /**
     * @param spaceName the top-level space name to check
     * @return true if the given space is a registered Confluence-like space
     */
    public boolean isSpace(String spaceName)
    {
        XWiki xwiki = xcontext.getWiki();
        DocumentReference webHomeRef = new DocumentReference(xcontext.getWikiId(), spaceName, "WebHome");
        try {
            XWikiDocument doc = xwiki.getDocument(webHomeRef, xcontext);
            return doc != null && !doc.isNew() && doc.getObject(SpaceConstants.SPACE_CODE_SPACE + "." + SpaceConstants.SPACE_CLASS_DOC) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * @param spaceName the top-level space name
     * @return the SpaceClass XObject from the space's WebHome, or null if not a space
     */
    public BaseObject getSpaceObject(String spaceName)
    {
        XWiki xwiki = xcontext.getWiki();
        DocumentReference webHomeRef = new DocumentReference(xcontext.getWikiId(), spaceName, "WebHome");
        try {
            XWikiDocument doc = xwiki.getDocument(webHomeRef, xcontext);
            if (doc != null && !doc.isNew()) {
                return doc.getObject(SpaceConstants.SPACE_CODE_SPACE + "." + SpaceConstants.SPACE_CLASS_DOC);
            }
        } catch (Exception e) {
            // fall through
        }
        return null;
    }

    /**
     * @return the top-level space name of the current document if it's a Confluence space, or null
     */
    public String getCurrentSpace()
    {
        XWikiDocument doc = xcontext.getDoc();
        if (doc == null) {
            return null;
        }
        DocumentReference docRef = doc.getDocumentReference();
        if (docRef == null) {
            return null;
        }
        List<SpaceReference> spaceRefs = docRef.getSpaceReferences();
        if (spaceRefs.isEmpty()) {
            return null;
        }
        // The first space reference is the top-level space (outermost in the hierarchy).
        String topLevelSpace = spaceRefs.get(0).getName();
        if (isSpace(topLevelSpace)) {
            return topLevelSpace;
        }
        return null;
    }

    /**
     * @return list of all space names in the current wiki
     */
    public List<String> getAllSpaces()
    {
        List<String> spaces = new ArrayList<>();
        XWiki xwiki = xcontext.getWiki();
        try {
            // Query all top-level spaces whose WebHome has a SpaceClass object
            String hql = "select distinct doc.space from XWikiDocument doc, "
                + "doc.object(SpaceCode.SpaceClass) as obj "
                + "where doc.name = 'WebHome'";
            List<String> results = xwiki.search(hql, xcontext);
            if (results != null) {
                for (String fullName : results) {
                    // fullName is like "SpaceName.WebHome"
                    int dotIndex = fullName.lastIndexOf('.');
                    if (dotIndex > 0) {
                        spaces.add(fullName.substring(0, dotIndex));
                    }
                }
            }
        } catch (Exception e) {
            // fall through
        }
        return spaces;
    }

    /**
     * Create a new Confluence-like space.
     *
     * @param spaceName the human-readable name of the space
     * @param spaceKey the short key for the space (used as the XWiki space name)
     * @param description optional description
     * @param owner user reference string for the space owner
     * @param isPersonal whether this is a personal space
     * @throws Exception if creation fails
     */
    public void createSpace(String spaceName, String spaceKey, String description,
        String owner, boolean isPersonal) throws Exception
    {
        XWiki xwiki = xcontext.getWiki();
        DocumentReference webHomeRef = new DocumentReference(xcontext.getWikiId(), spaceKey, "WebHome");
        XWikiDocument doc = xwiki.getDocument(webHomeRef, xcontext);

        if (doc.isNew()) {
            BaseObject obj = doc.newObject(SpaceConstants.SPACE_CODE_SPACE + "." + SpaceConstants.SPACE_CLASS_DOC, xcontext);
            obj.setStringValue(SpaceConstants.FIELD_NAME, spaceName);
            obj.setStringValue(SpaceConstants.FIELD_KEY, spaceKey);
            obj.setStringValue(SpaceConstants.FIELD_DESCRIPTION, description != null ? description : "");
            obj.setStringValue(SpaceConstants.FIELD_OWNER, owner != null ? owner : "");
            obj.setStringValue(SpaceConstants.FIELD_TYPE,
                isPersonal ? SpaceConstants.FIELD_VALUE_TYPE_PERSONAL : SpaceConstants.FIELD_VALUE_TYPE_PUBLIC);
            obj.setStringValue(SpaceConstants.FIELD_HOMEPAGE, "");
            xwiki.saveDocument(doc, "Create space: " + spaceName, xcontext);
        }
    }
}
