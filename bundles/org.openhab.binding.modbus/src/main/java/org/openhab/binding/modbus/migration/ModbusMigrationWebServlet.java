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

import java.io.IOException;
import java.util.Map;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.http.whiteboard.HttpWhiteboardConstants;

/**
 * Same-origin static migration view; privileged operations belong to the authenticated Core REST application.
 *
 * @author Sami Salonen - Initial contribution
 */
@NonNullByDefault
@Component(service = Servlet.class, property = {
        HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_PATTERN + "=/modbus/migration/*",
        HttpWhiteboardConstants.HTTP_WHITEBOARD_SERVLET_NAME + "=modbus-migration-view" })
public class ModbusMigrationWebServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Map<String, String> CONTENT_TYPES = Map.of("index.html", "text/html", "auth.mjs",
            "text/javascript", "controller.mjs", "text/javascript", "app.mjs", "text/javascript", "style.css",
            "text/css", "tile.svg", "image/svg+xml");

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getPathInfo();
        if (path == null || path.isEmpty()) {
            response.sendRedirect("/modbus/migration/");
            return;
        }
        String asset = "/".equals(path) ? "index.html" : path.substring(1);
        String type = CONTENT_TYPES.get(asset);
        if (type == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        try (var stream = getClass().getResourceAsStream("/migration/" + asset)) {
            if (stream == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            response.setCharacterEncoding("UTF-8");
            response.setContentType(type);
            response.setHeader("X-Content-Type-Options", "nosniff");
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Referrer-Policy", "same-origin");
            response.setHeader("Content-Security-Policy",
                    "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'self'");
            stream.transferTo(response.getOutputStream());
        }
    }
}
