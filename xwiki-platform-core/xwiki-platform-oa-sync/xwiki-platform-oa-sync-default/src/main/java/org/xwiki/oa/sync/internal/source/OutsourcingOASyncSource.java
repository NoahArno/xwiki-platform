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

import java.nio.file.Path;

import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.oa.sync.OASyncSource;
import org.xwiki.oa.sync.model.OASyncParseResult;

/**
 * 业务外包用户数据来源：文件格式未定义，暂为占位实现。默认未启用
 * （{@code xwiki.oa-sync.source.outsourcing.enabled=false}）。格式定稿后在此实现 {@link #parse(Path)}。
 *
 * @version $Id$
 */
@Component(roles = OASyncSource.class)
@Named("outsourcing")
@Singleton
public class OutsourcingOASyncSource extends AbstractOASyncSource
{
    @Override
    public String getType()
    {
        return "outsourcing";
    }

    @Override
    public OASyncParseResult parse(Path file)
    {
        throw new UnsupportedOperationException("外包用户文件格式未定义，解析尚未实现");
    }
}
