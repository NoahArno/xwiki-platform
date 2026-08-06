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
package org.xwiki.oa.sync.internal.ftp;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.net.ftp.FTPClient;
import org.junit.jupiter.api.Test;
import org.xwiki.oa.sync.OASyncConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OAFtpDownloader}.
 *
 * @version $Id$
 */
class OAFtpDownloaderTest
{
    private final FTPClient client = mock(FTPClient.class);

    private OAFtpDownloader newDownloader(boolean loginOk, boolean retrieveOk) throws Exception
    {
        when(this.client.login(anyString(), anyString())).thenReturn(loginOk);
        when(this.client.retrieveFile(anyString(), any(OutputStream.class)))
            .thenAnswer(inv -> {
                if (retrieveOk) {
                    ((OutputStream) inv.getArguments()[1]).write(new byte[] { 1, 2, 3 });
                }
                return retrieveOk;
            });
        when(this.client.getReplyString()).thenReturn("mock-reply");

        OASyncConfiguration config = mock(OASyncConfiguration.class);
        when(config.getFtpHost()).thenReturn("ftp.example.com");
        when(config.getFtpPort()).thenReturn(21);
        when(config.getFtpUsername()).thenReturn("u");
        when(config.getFtpPassword()).thenReturn("p");
        when(config.getFtpTimeoutMs()).thenReturn(5000);

        OAFtpDownloader downloader = new OAFtpDownloader();
        downloader.configuration = config;
        downloader.clientFactory = () -> this.client;
        return downloader;
    }

    @Test
    void downloadsToTempFileAndLogsOut() throws Exception
    {
        OAFtpDownloader downloader = this.newDownloader(true, true);
        Path file = downloader.download("/comm/bdpp/oa/20260805/IOA_EMPLOYEE_JGTY_20260805.dat");
        assertTrue(Files.exists(file));
        assertEquals(3, Files.size(file));
        verify(this.client).logout();
        verify(this.client).disconnect();
        Files.deleteIfExists(file);
    }

    @Test
    void throwsWhenLoginFails() throws Exception
    {
        OAFtpDownloader downloader = this.newDownloader(false, true);
        assertThrows(IOException.class, () -> downloader.download("/x.dat"));
    }

    @Test
    void throwsWhenRetrieveFails() throws Exception
    {
        OAFtpDownloader downloader = this.newDownloader(true, false);
        assertThrows(IOException.class, () -> downloader.download("/x.dat"));
        verify(this.client).logout();
        verify(this.client).disconnect();
    }
}
