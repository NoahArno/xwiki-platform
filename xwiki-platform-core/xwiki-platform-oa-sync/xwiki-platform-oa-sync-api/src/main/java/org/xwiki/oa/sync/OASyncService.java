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
package org.xwiki.oa.sync;

import java.time.LocalDate;
import java.util.List;

import org.xwiki.component.annotation.Role;
import org.xwiki.oa.sync.model.OASyncResult;
import org.xwiki.oa.sync.model.OASyncTriggerType;

/**
 * Entry point for running OA user data syncs.
 *
 * @version $Id$
 */
@Role
public interface OASyncService
{
    /**
     * Run all enabled sources.
     *
     * @param triggerType how the sync was triggered
     * @param manualDate optional data date override for manual backfill; {@code null} = default (offset from config)
     * @return one result per enabled source (disabled sources are skipped and not present)
     */
    List<OASyncResult> syncAll(OASyncTriggerType triggerType, LocalDate manualDate);
}
