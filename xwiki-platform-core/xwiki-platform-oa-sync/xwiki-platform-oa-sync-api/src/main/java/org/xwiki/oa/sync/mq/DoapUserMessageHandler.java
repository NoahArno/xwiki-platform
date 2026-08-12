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
package org.xwiki.oa.sync.mq;

import org.xwiki.component.annotation.Role;
import org.xwiki.oa.sync.mq.model.DoapUserMessage;

/**
 * Processes one DOAP user MQ message for a specific tag.
 * <p>
 * Implementations are components named by the tag they handle (e.g.
 * {@link DoapMqConstants#TAG_USER_CREATE}).
 *
 * @version $Id$
 */
@Role
public interface DoapUserMessageHandler
{
    /**
     * @return the tag this handler processes
     */
    String getTag();

    /**
     * Handle a consumed message.
     *
     * @param message the parsed message
     * @throws Exception if the message cannot be processed (it will then be retried by MQ)
     */
    void handle(DoapUserMessage message) throws Exception;
}
