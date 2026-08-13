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
package org.xwiki.audit.log.internal;

import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.LocalDocumentReference;

import com.xpn.xwiki.doc.AbstractMandatoryClassInitializer;
import com.xpn.xwiki.objects.classes.BaseClass;

/**
 * Defines {@code AuditLog.LogRecordClass}: one object per audit event.
 *
 * @version $Id$
 * @since 18.1.0
 */
@Component
@Named("AuditLog.LogRecordClass")
@Singleton
public class AuditLogRecordClassInitializer extends AbstractMandatoryClassInitializer
{
    /**
     * The reference of the audit log record class.
     */
    public static final LocalDocumentReference REFERENCE = new LocalDocumentReference("AuditLog", "LogRecordClass");

    /**
     * Default constructor.
     */
    public AuditLogRecordClassInitializer()
    {
        super(REFERENCE);
    }

    @Override
    protected void createClass(BaseClass xclass)
    {
        xclass.addTextField("eventType", "Event Type", 30);
        xclass.addTextField("channel", "Channel", 30);
        xclass.addTextField("userName", "User Name", 60);
        xclass.addTextField("ip", "IP", 40);
        xclass.addTextAreaField("detail", "Detail", 60, 5);
        xclass.addDateField("eventTime", "Event Time");
    }
}
