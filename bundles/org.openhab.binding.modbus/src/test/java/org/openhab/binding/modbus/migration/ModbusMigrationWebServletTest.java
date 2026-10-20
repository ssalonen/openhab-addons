/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.modbus.migration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.jupiter.api.Test;

/** @author Sami Salonen - Initial contribution */
class ModbusMigrationWebServletTest {
    @Test
    void servesIndexAndAllLocalAssetsWithSecurityHeaders() throws IOException {
        for (String path : new String[] { "/", "/index.html", "/auth.mjs", "/controller.mjs", "/app.mjs", "/style.css",
                "/tile.svg" }) {
            var request = mock(HttpServletRequest.class);
            var response = mock(HttpServletResponse.class);
            when(request.getPathInfo()).thenReturn(path);
            var bytes = new ByteArrayOutputStream();
            when(response.getOutputStream()).thenReturn(new ServletOutputStream() {
                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener listener) {
                }

                @Override
                public void write(int value) {
                    bytes.write(value);
                }
            });
            new ModbusMigrationWebServlet().doGet(request, response);
            assertTrue(bytes.size() > 0, path);
            verify(response).setHeader("X-Content-Type-Options", "nosniff");
            verify(response).setHeader(eq("Content-Security-Policy"), contains("default-src 'self'"));
            if ("/".equals(path))
                assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("Modbus migration"));
        }
    }

    @Test
    void unknownAndTraversalPathsAreNotServed() throws IOException {
        for (String path : new String[] { "/../NOTICE", "/%2e%2e/NOTICE", "/private.json", "/nested/index.html" }) {
            var request = mock(HttpServletRequest.class);
            var response = mock(HttpServletResponse.class);
            when(request.getPathInfo()).thenReturn(path);
            new ModbusMigrationWebServlet().doGet(request, response);
            verify(response).sendError(404);
            verify(response, never()).getOutputStream();
        }
    }

    @Test
    void directoryWithoutSlashRedirectsToCanonicalSameOriginPath() throws IOException {
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        new ModbusMigrationWebServlet().doGet(request, response);
        verify(response).sendRedirect("/modbus/migration/");
    }
}
