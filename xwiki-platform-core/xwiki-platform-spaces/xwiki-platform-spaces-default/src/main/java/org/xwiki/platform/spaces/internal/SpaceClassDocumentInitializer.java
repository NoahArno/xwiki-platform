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

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.LocalDocumentReference;
import org.xwiki.platform.spaces.SpaceConstants;
import org.xwiki.sheet.SheetBinder;

import com.xpn.xwiki.doc.AbstractMandatoryClassInitializer;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.classes.BaseClass;

/**
 * Update the SpaceCode.SpaceClass document with all required information.
 * This class defines the metadata structure for Confluence-like spaces in XWiki.
 *
 * @version $Id$
 * @since 18.1.0
 */
@Component
@Named("SpaceCode.SpaceClass")
@Singleton
public class SpaceClassDocumentInitializer extends AbstractMandatoryClassInitializer
{
    /**
     * Reference to the SpaceClass document.
     */
    public static final LocalDocumentReference SPACE_CLASS =
        new LocalDocumentReference(SpaceConstants.SPACE_CODE_SPACE, SpaceConstants.SPACE_CLASS_DOC);

    @Inject
    @Named("class")
    private SheetBinder classSheetBinder;

    /**
     * Default constructor.
     */
    public SpaceClassDocumentInitializer()
    {
        super(SPACE_CLASS);
    }

    @Override
    protected void createClass(BaseClass xclass)
    {
        xclass.addTextField(SpaceConstants.FIELD_NAME, SpaceConstants.FIELDPN_NAME, 30);
        xclass.addTextField(SpaceConstants.FIELD_KEY, SpaceConstants.FIELDPN_KEY, 30);
        xclass.addTextAreaField(SpaceConstants.FIELD_DESCRIPTION, SpaceConstants.FIELDPN_DESCRIPTION, 40, 5);
        xclass.addTextField(SpaceConstants.FIELD_ICON, SpaceConstants.FIELDPN_ICON, 30);
        xclass.addUsersField(SpaceConstants.FIELD_OWNER, SpaceConstants.FIELDPN_OWNER, false);
        xclass.addStaticListField(SpaceConstants.FIELD_TYPE, SpaceConstants.FIELDPN_TYPE, SpaceConstants.FIELDL_TYPE);
        xclass.addPageField(SpaceConstants.FIELD_HOMEPAGE, SpaceConstants.FIELDPN_HOMEPAGE, 30);
    }

    @Override
    public boolean updateDocument(XWikiDocument document)
    {
        boolean needsUpdate = super.updateDocument(document);

        // Use SpaceCode.SpaceSheet to display documents having SpaceClass objects if no other class sheet is specified.
        if (this.classSheetBinder.getSheets(document).isEmpty()) {
            String wikiName = document.getDocumentReference().getWikiReference().getName();
            DocumentReference sheet = new DocumentReference(wikiName,
                SpaceConstants.SPACE_CODE_SPACE, "SpaceSheet");
            needsUpdate |= this.classSheetBinder.bind(document, sheet);
        }

        return needsUpdate;
    }
}
