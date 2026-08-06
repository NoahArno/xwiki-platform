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
package org.xwiki.oa.sync.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of parsing a data file: valid records plus per-line errors.
 *
 * @version $Id$
 */
public class OASyncParseResult
{
    private final List<OAUserRecord> records = new ArrayList<>();

    private final List<String> errors = new ArrayList<>();

    /**
     * @return the valid records parsed from the file
     */
    public List<OAUserRecord> getRecords()
    {
        return this.records;
    }

    /**
     * @return the per-line parse errors (bad rows that were skipped)
     */
    public List<String> getErrors()
    {
        return this.errors;
    }
}
