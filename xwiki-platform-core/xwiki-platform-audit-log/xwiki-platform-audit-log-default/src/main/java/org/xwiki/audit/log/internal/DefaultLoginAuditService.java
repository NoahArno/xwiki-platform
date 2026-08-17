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

import java.util.Date;
import java.util.List;
import java.util.UUID;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.audit.log.LoginAuditService;
import org.xwiki.audit.log.LoginChannel;
import org.xwiki.audit.log.LoginEventType;
import org.xwiki.component.annotation.Component;
import org.xwiki.configuration.ConfigurationSource;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.query.Query;
import org.xwiki.query.QueryManager;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/**
 * Default {@link LoginAuditService}: persists one {@code AuditLog.LogRecordClass} document per event and keeps only
 * the latest {@value #MAX_RECORDS} records. Honors the {@code xwiki.authentication.audit-log.enabled} toggle and never
 * throws, so audit logging can never break the login/logout flow.
 *
 * @version $Id$
 * @since 18.1.0
 */
@Component(roles = LoginAuditService.class)
@Singleton
public class DefaultLoginAuditService implements LoginAuditService
{
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultLoginAuditService.class);

    /**
     * xwiki.cfg key enabling/disabling audit logging (default enabled).
     */
    public static final String CONFIG_ENABLED = "xwiki.authentication.audit-log.enabled";

    private static final String RECORD_SPACE = "AuditLog";

    private static final String RECORD_CLASS = "LogRecordClass";

    private static final int MAX_RECORDS = 100;

    @Inject
    @Named("xwikicfg")
    private ConfigurationSource configuration;

    @Inject
    private QueryManager queryManager;

    @Inject
    private Provider<XWikiContext> contextProvider;

    @Override
    public void record(LoginEventType eventType, LoginChannel channel, String userName, String ip, String detail)
    {
        try {
            if (!isEnabled()) {
                return;
            }
            XWikiContext context = this.contextProvider.get();
            String wikiId = context.getWikiId();
            String name = eventType.name() + "-" + UUID.randomUUID();
            DocumentReference recordRef = new DocumentReference(wikiId, RECORD_SPACE, name);
            XWikiDocument doc = new XWikiDocument(recordRef);
            doc.setTitle(eventType.getDisplayName());
            doc.setHidden(true);

            BaseObject obj = doc.newXObject(new DocumentReference(wikiId, RECORD_SPACE, RECORD_CLASS), context);
            obj.setStringValue("eventType", eventType.name());
            obj.setStringValue("channel", channel != null ? channel.name() : "");
            obj.setStringValue("userName", userName != null ? userName : "");
            obj.setStringValue("ip", ip != null ? ip : "");
            obj.setLargeStringValue("detail", detail != null ? detail : "");
            obj.setDateValue("eventTime", new Date());

            context.getWiki().saveDocument(doc, "Audit log: " + eventType.name(), context);
            prune(wikiId);
        } catch (Exception e) {
            // Audit logging must never break the login/logout flow.
            LOGGER.warn("Failed to record audit log event [{}] for user [{}]", eventType, userName, e);
        }
    }

    private void prune(String wikiId)
    {
        try {
            Query query = this.queryManager.createQuery(
                "select doc.fullName from Document doc where doc.object(" + RECORD_SPACE + "." + RECORD_CLASS
                    + ") order by doc.creationDate desc",
                Query.XWQL);
            List<String> fullNames = query.<String>execute();
            if (fullNames.size() <= MAX_RECORDS) {
                return;
            }
            XWikiContext context = this.contextProvider.get();
            for (String fullName : fullNames.subList(MAX_RECORDS, fullNames.size())) {
                context.getWiki().deleteDocument(context.getWiki().getDocument(fullName, context), context);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to prune audit log records", e);
        }
    }

    private boolean isEnabled()
    {
        // A missing key means enabled (the feature is on by default). Note that we cannot rely on
        // getProperty(key, Boolean.class) returning null for a missing key: the xwikicfg ConfigurationSource
        // converts a missing Boolean property to Boolean.FALSE rather than null, so we explicitly check for
        // the presence of the key first.
        return !this.configuration.containsKey(CONFIG_ENABLED)
            || Boolean.TRUE.equals(this.configuration.getProperty(CONFIG_ENABLED, Boolean.class));
    }
}
