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

import java.nio.file.Path;

import org.xwiki.component.annotation.Role;
import org.xwiki.oa.sync.model.OASyncParseResult;

/**
 * A user-data source (employee / outsourcing). Implementations are components named by their type.
 *
 * @version $Id$
 */
@Role
public interface OASyncSource
{
    /**
     * @return the source type, e.g. {@code employee} or {@code outsourcing}
     */
    String getType();

    /**
     * @return whether this source is enabled in the configuration
     */
    boolean isEnabled();

    /**
     * Resolve the remote FTP path for the given data date.
     *
     * @param dateYYYYMMDD the data date
     * @return the remote file path
     */
    String resolveRemotePath(String dateYYYYMMDD);

    /**
     * Parse a downloaded file into records and line-level errors.
     *
     * @param file the downloaded temporary file
     * @return the parse result
     * @throws Exception if the file cannot be processed at all
     */
    OASyncParseResult parse(Path file) throws Exception;
}
