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

/**
 * A single user record parsed from an OA data file.
 *
 * @version $Id$
 */
public class OAUserRecord
{
    private final String username;

    private final String orgCode;

    private final String orgName;

    private final boolean active;

    /**
     * @param username the employee number used as the XWiki username
     * @param orgCode the unique organization code (permission group unique key)
     * @param orgName the Chinese organization name (group display name)
     * @param active whether the user should be active (enabled) in XWiki
     */
    public OAUserRecord(String username, String orgCode, String orgName, boolean active)
    {
        this.username = username;
        this.orgCode = orgCode;
        this.orgName = orgName;
        this.active = active;
    }

    /**
     * @return the employee number (XWiki username)
     */
    public String getUsername()
    {
        return this.username;
    }

    /**
     * @return the unique organization code
     */
    public String getOrgCode()
    {
        return this.orgCode;
    }

    /**
     * @return the Chinese organization name
     */
    public String getOrgName()
    {
        return this.orgName;
    }

    /**
     * @return true if the user should be enabled
     */
    public boolean isActive()
    {
        return this.active;
    }
}
