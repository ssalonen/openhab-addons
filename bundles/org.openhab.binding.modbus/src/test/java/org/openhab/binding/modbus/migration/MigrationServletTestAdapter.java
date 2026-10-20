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

import static org.mockito.Mockito.*;

import java.io.IOException;
import java.io.InputStream;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * Test-only adapter for the non-servlet Jersey HTTP server. It supplies a servlet stream explicitly;
 * it does NOT verify servlet context injection, Core authentication, or CXF provider selection.
 * Responses still pass through Core's actual MediaTypeExtension on loopback HTTP.
 *
 * @author Sami Salonen - Initial contribution
 */
@Path("modbus/migration")
@Produces(MediaType.APPLICATION_JSON)
public class MigrationServletTestAdapter {
    private final ModbusMigrationResource resource;

    public MigrationServletTestAdapter(ModbusMigrationResource resource) {
        this.resource = resource;
    }

    @POST
    @Path("previews")
    public Response preview() {
        return resource.preview();
    }

    @GET
    @Path("provider-previews")
    public Response providerPreview() {
        return resource.providerPreview();
    }

    @GET
    @Path("workflows/{identity}")
    public Response report(@PathParam("identity") String identity) {
        return resource.report(identity);
    }

    @POST
    @Path("workflows/{identity}/apply")
    public Response apply(@PathParam("identity") String identity) {
        return resource.apply(identity);
    }

    @POST
    @Path("workflows/{identity}/validate")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response validate(@PathParam("identity") String identity, @Context ContainerRequestContext context)
            throws IOException {
        return resource.validate(identity, request(context.getEntityStream()));
    }

    @POST
    @Path("workflows/{identity}/cutover")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response cutover(@PathParam("identity") String identity, @Context ContainerRequestContext context)
            throws IOException {
        return resource.cutover(identity, request(context.getEntityStream()));
    }

    @POST
    @Path("workflows/{identity}/revert")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response revert(@PathParam("identity") String identity, @Context ContainerRequestContext context)
            throws IOException {
        return resource.revert(identity, request(context.getEntityStream()));
    }

    @POST
    @Path("workflows/{identity}/rollback")
    public Response rollback(@PathParam("identity") String identity) {
        return resource.rollback(identity);
    }

    static HttpServletRequest request(InputStream input) throws IOException {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getInputStream()).thenReturn(new ServletInputStream() {
            @Override
            public int read() throws IOException {
                return input.read();
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                return input.read(bytes, offset, length);
            }

            @Override
            public boolean isFinished() {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
                throw new UnsupportedOperationException();
            }
        });
        return request;
    }
}
