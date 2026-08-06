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
package org.xwiki.oa.sync.internal.initializer;

import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.LocalDocumentReference;

import com.xpn.xwiki.doc.AbstractMandatoryClassInitializer;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.classes.BaseClass;

/**
 * Defines {@code OASync.OASyncOrgGroupClass} attached to org group documents (orgCode unique, orgName display).
 *
 * @version $Id$
 */
@Component
@Named("OASync.OASyncOrgGroupClass")
@Singleton
public class OASyncOrgGroupClassInitializer extends AbstractMandatoryClassInitializer
{
    /**
     * The reference of the org group class.
     */
    public static final LocalDocumentReference REFERENCE =
        new LocalDocumentReference("OASync", "OASyncOrgGroupClass");

    /**
     * Default constructor.
     */
    public OASyncOrgGroupClassInitializer()
    {
        super(REFERENCE);
    }

    @Override
    protected void createClass(BaseClass xclass)
    {
        xclass.addTextField("orgCode", "Org Code", 30);
        xclass.addTextField("orgName", "Org Name", 60);
    }

    @Override
    public boolean updateDocument(XWikiDocument document)
    {
        return super.updateDocument(document);
    }
}
