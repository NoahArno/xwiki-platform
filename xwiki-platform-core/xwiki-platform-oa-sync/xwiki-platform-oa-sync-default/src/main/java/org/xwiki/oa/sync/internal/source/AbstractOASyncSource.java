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
package org.xwiki.oa.sync.internal.source;

import javax.inject.Inject;

import org.xwiki.oa.sync.OASyncConfiguration;
import org.xwiki.oa.sync.OASyncSource;

/**
 * Base class for OA sync sources: enabled flag and remote path resolution from per-source config.
 *
 * @version $Id$
 */
public abstract class AbstractOASyncSource implements OASyncSource
{
    @Inject
    OASyncConfiguration configuration;

    @Override
    public boolean isEnabled()
    {
        return this.configuration.isSourceEnabled(getType());
    }

    @Override
    public String resolveRemotePath(String dateYYYYMMDD)
    {
        String basePath = this.configuration.getSourceBasePath(getType()).replace("%s", dateYYYYMMDD);
        String filePattern = this.configuration.getSourceFilePattern(getType()).replace("%s", dateYYYYMMDD);
        return basePath.endsWith("/") ? basePath + filePattern : basePath + "/" + filePattern;
    }
}
