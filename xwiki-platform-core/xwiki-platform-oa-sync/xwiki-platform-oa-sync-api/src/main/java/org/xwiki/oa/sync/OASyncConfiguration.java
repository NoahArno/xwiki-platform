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

import org.xwiki.component.annotation.Role;

/**
 * Configuration for the OA sync, read from {@code xwiki.cfg}.
 *
 * @version $Id$
 */
@Role
public interface OASyncConfiguration
{
    /**
     * @return the FTP host
     */
    String getFtpHost();

    /**
     * @return the FTP port
     */
    int getFtpPort();

    /**
     * @return the FTP username
     */
    String getFtpUsername();

    /**
     * @return the FTP password
     */
    String getFtpPassword();

    /**
     * @return the FTP connect/data timeout in milliseconds
     */
    int getFtpTimeoutMs();

    /**
     * @param type the source type (employee / outsourcing)
     * @return whether the source is enabled
     */
    boolean isSourceEnabled(String type);

    /**
     * @param type the source type
     * @return the remote base path template (may contain {@code %s} for YYYYMMDD)
     */
    String getSourceBasePath(String type);

    /**
     * @param type the source type
     * @return the remote file name template (may contain {@code %s} for YYYYMMDD)
     */
    String getSourceFilePattern(String type);

    /**
     * @param type the source type
     * @return the file encoding, default GBK
     */
    String getSourceEncoding(String type);

    /**
     * @param type the source type
     * @return how many days before today the data date is, default 1 (yesterday)
     */
    int getSourceDateOffsetDays(String type);

    /**
     * @return the number of worker threads used to create users during the outsourcing Excel import,
     *         default 4, clamped to 1..8
     */
    int getExcelImportThreads();

    /**
     * 数据文件里归属机构编号为空时使用的默认机构编号。
     *
     * @return the default organization code, {@code 0} unless overridden by configuration
     */
    default String getDefaultOrgCode()
    {
        return "0";
    }

    /**
     * 数据文件里归属机构编号为空时使用的默认机构名称。
     *
     * @return the default organization name, 未知机构 unless overridden by configuration
     */
    default String getDefaultOrgName()
    {
        return "未知机构";
    }

    /**
     * @return the wiki space where sync record and org-group classes/pages live
     */
    default String getRecordSpace()
    {
        return "OASync";
    }
}
