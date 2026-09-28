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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Confluence 数据库的只读访问封装。
 *
 * <p>刻意保持"薄"：只负责连接生命周期、参数绑定和结果集到对象的转换，
 * 具体 SQL 由各调用方（体检、导出）持有，便于按需调整。</p>
 */
public final class ConfluenceDb implements AutoCloseable
{
    private final Connection connection;

    private ConfluenceDb(Connection connection)
    {
        this.connection = connection;
    }

    /**
     * 把结果集的一行转换成对象。
     *
     * @param <T> 目标类型
     */
    @FunctionalInterface
    public interface RowMapper<T>
    {
        /**
         * @param rs 当前指向有效行的结果集
         * @return 转换后的对象
         * @throws SQLException 读取失败时
         */
        T map(ResultSet rs) throws SQLException;
    }

    /**
     * 建立连接。
     *
     * @param config 数据库配置
     * @return 数据库访问对象
     * @throws SQLException 连接失败时
     */
    public static ConfluenceDb open(DbConfig config) throws SQLException
    {
        try {
            // 显式加载驱动，便于在缺少驱动时给出清晰错误
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException(
                "找不到 MySQL JDBC 驱动。请确认 com.mysql:mysql-connector-j 在运行期 classpath 上。", e);
        }

        Connection connection = DriverManager.getConnection(config.jdbcUrl(), config.username(), config.password());
        connection.setReadOnly(true);
        return new ConfluenceDb(connection);
    }

    /**
     * 执行查询并收集全部结果。
     *
     * @param <T> 结果类型
     * @param sql 带 {@code ?} 占位符的 SQL
     * @param mapper 行转换器
     * @param parameters 占位符参数
     * @return 结果列表
     * @throws SQLException 查询失败时
     */
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... parameters) throws SQLException
    {
        List<T> results = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    results.add(mapper.map(rs));
                }
            }
        }
        return results;
    }

    /**
     * 执行查询并取第一行。
     *
     * @param <T> 结果类型
     * @param sql 带 {@code ?} 占位符的 SQL
     * @param mapper 行转换器
     * @param parameters 占位符参数
     * @return 第一行，无结果时为 {@code null}
     * @throws SQLException 查询失败时
     */
    public <T> T querySingle(String sql, RowMapper<T> mapper, Object... parameters) throws SQLException
    {
        List<T> results = query(sql, mapper, parameters);
        return results.isEmpty() ? null : results.get(0);
    }

    private static void bind(PreparedStatement statement, Object... parameters) throws SQLException
    {
        for (int i = 0; i < parameters.length; i++) {
            statement.setObject(i + 1, parameters[i]);
        }
    }

    /**
     * @return 底层连接，供少量需要自定义 JDBC 行为的调用方使用
     */
    public Connection connection()
    {
        return connection;
    }

    @Override
    public void close() throws SQLException
    {
        connection.close();
    }
}
