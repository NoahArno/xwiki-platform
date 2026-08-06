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

import org.junit.jupiter.api.Test;
import org.xwiki.oa.sync.OASyncConfiguration;
import org.xwiki.oa.sync.model.OASyncParseResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AbstractOASyncSource}.
 *
 * @version $Id$
 */
class AbstractOASyncSourceTest
{
    private final OASyncConfiguration config = mock(OASyncConfiguration.class);

    private AbstractOASyncSource newSource()
    {
        return new AbstractOASyncSource()
        {
            @Override
            public String getType()
            {
                return "employee";
            }

            @Override
            public OASyncParseResult parse(Path file)
            {
                return new OASyncParseResult();
            }
        };
    }

    @Test
    void resolvesRemotePathWithDate()
    {
        AbstractOASyncSource source = this.newSource();
        source.configuration = this.config;
        when(this.config.getSourceBasePath("employee")).thenReturn("/comm/bdpp/oa/%s");
        when(this.config.getSourceFilePattern("employee")).thenReturn("IOA_EMPLOYEE_JGTY_%s.dat");

        assertEquals("/comm/bdpp/oa/20260805/IOA_EMPLOYEE_JGTY_20260805.dat",
            source.resolveRemotePath("20260805"));
    }

    @Test
    void enabledComesFromConfig()
    {
        AbstractOASyncSource source = this.newSource();
        source.configuration = this.config;
        when(this.config.isSourceEnabled("employee")).thenReturn(true);
        assertTrue(source.isEnabled());
        when(this.config.isSourceEnabled("employee")).thenReturn(false);
        assertFalse(source.isEnabled());
    }
}
