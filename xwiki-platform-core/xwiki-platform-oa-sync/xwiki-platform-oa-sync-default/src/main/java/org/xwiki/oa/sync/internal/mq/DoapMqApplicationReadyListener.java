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
package org.xwiki.oa.sync.internal.mq;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.xwiki.bridge.event.ApplicationReadyEvent;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.phase.Initializable;
import org.xwiki.observation.AbstractEventListener;
import org.xwiki.observation.event.Event;

import com.xpn.xwiki.XWikiContext;

/**
 * Starts the DOAP MQ consumer once the application is ready (and immediately on component
 * initialization when the application is already running, e.g. hot deployment of the extension).
 *
 * @version $Id$
 */
@Component
@Singleton
@Named("org.xwiki.oa.sync.internal.mq.DoapMqApplicationReadyListener")
public class DoapMqApplicationReadyListener extends AbstractEventListener implements Initializable
{
    @Inject
    private DefaultDoapMqConsumer consumer;

    @Inject
    @Named("readonly")
    private Provider<XWikiContext> contextProvider;

    /**
     * Default constructor: listen for {@link ApplicationReadyEvent}.
     */
    public DoapMqApplicationReadyListener()
    {
        super("DoapMqApplicationReadyListener", new ApplicationReadyEvent());
    }

    @Override
    public void onEvent(Event event, Object source, Object data)
    {
        this.consumer.start();
    }

    @Override
    public void initialize()
    {
        // 应用已就绪时（如扩展热部署）立即启动消费线程
        if (this.contextProvider.get() != null) {
            this.consumer.start();
        }
    }
}
