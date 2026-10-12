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
package org.xwiki.contrib.confluence.migration.db;

import java.util.Objects;

/**
 * 连接 Confluence 数据库所需的配置。
 *
 * <p>从 {@code confluence.cfg.xml} 读取请用 {@link ConfluenceHome}；
 * 也可以直接构造以显式指定 JDBC 参数（生产环境的密码可能是加密的，此时只能显式传入）。</p>
 *
 * @param jdbcUrl JDBC 连接串
 * @param username 数据库用户名
 * @param password 数据库密码
 */
public record DbConfig(String jdbcUrl, String username, String password)
{
    public DbConfig
    {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
    }

    /**
     * @return 不含密码的可打印描述，用于日志
     */
    public String describe()
    {
        return jdbcUrl + " (user=" + username + ")";
    }
}
