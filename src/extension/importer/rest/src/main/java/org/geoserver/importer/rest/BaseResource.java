/* Copyright (c) 2001 - 2013 OpenPlans - www.openplans.org. All rights reserved.
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.importer.rest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.geoserver.importer.ImportContext;
import org.geoserver.importer.ImportTask;
import org.geoserver.importer.Importer;
import org.geoserver.importer.ValidationException;
import org.geoserver.rest.AbstractResource;
import org.geoserver.rest.RestletException;
import org.geotools.util.logging.Logging;
import org.restlet.data.Status;

/**
 * Provide common functionality for import resources. As Resource objects
 * are essentially singletons in the spring application context, it is critical
 * that they are stateless or thread-safe. Another goal is to unify error
 * handling.
 */
public abstract class BaseResource extends AbstractResource {

    /* since we're a singleton, no need for static */
    protected final Logger LOGGER = Logging.getLogger(getClass());
    protected final Importer importer;

    protected BaseResource(Importer importer) {
        this.importer = importer;
    }

    @Override
    public final void handlePut() {
        try {
            handlePutInternal();
        } catch (Throwable t) {
            handleException(t);
        }
    }

    @Override
    public final void handlePost() {
        try {
            handlePostInternal();
        } catch (Throwable t) {
            handleException(t);
        }
    }

    @Override
    public final void handleGet() {
        try {
            handleGetInternal();
        } catch (Throwable t) {
            handleException(t);
        }
    }

    @Override
    public final void handleDelete() {
        try {
            handleDeleteInternal();
        } catch (Throwable t) {
            handleException(t);
        }
    }

    private void handleException(Throwable t) {
        if (t instanceof RestletException) {
            throw (RestletException) t;
        }
        if (t instanceof ValidationException) {
            throw ImportJSONWriter.badRequest(t.getMessage());
        }
        unexpectedError(t);
    }

    protected final void unexpectedError(Throwable cause) throws RestletException {
        UUID uuid = UUID.randomUUID();
        String msg = "Unexpected exception " + uuid + " while processing " + getRequest().getResourceRef();
        if (LOGGER.isLoggable(Level.SEVERE)) {
            LOGGER.log(Level.SEVERE, msg, cause);
        } else {
            System.err.println(msg);
            cause.printStackTrace();
        }
        throw new RestletException("Unexpected error " + uuid, Status.SERVER_ERROR_INTERNAL);
    }

    protected ImportContext context() {
        return context(false);
    }

    protected ImportContext context(boolean optional) {
        long i = Long.parseLong(getAttribute("import"));

        ImportContext context = importer.getContext(i);
        if (!optional && context == null) {
            throw new RestletException("No such import: " + i, Status.CLIENT_ERROR_NOT_FOUND);
        }
        return context;
    }

    protected ImportTask task() {
        return task(false);
    }

    protected ImportTask task(boolean optional) {
        ImportContext context = context();
        ImportTask task = null;

        String t = getAttribute("task");
        if (t != null) {
            int id = Integer.parseInt(t);
            task = context.task(id);
        }
        if (t != null && task == null) {
            throw new RestletException("No such task: " + t + " for import: " + context.getId(),
                    Status.CLIENT_ERROR_NOT_FOUND);
        }

        if (task == null && !optional) {
            throw new RestletException("No task specified", 
                    
                    Status.CLIENT_ERROR_NOT_FOUND);
        }

        return task;
    }

    protected int expand(int def) {
        String ex = getRequest().getResourceRef().getQueryAsForm().getFirstValue("expand");
        if (ex == null) {
            return def;
        }

        try {
            return "self".equalsIgnoreCase(ex) ? 1
                 : "all".equalsIgnoreCase(ex) ? Integer.MAX_VALUE 
                 : "none".equalsIgnoreCase(ex) ? 0 
                 : Integer.parseInt(ex);
        }
        catch(NumberFormatException e) {
            return def;
        }
    }

    protected ImportJSONReader newReader(InputStream input) throws IOException {
        return new ImportJSONReader(importer, input);
    }

    protected ImportJSONWriter newWriter(OutputStream output) throws IOException {
        return new ImportJSONWriter(importer, getPageInfo(), output);
    }

    protected void handlePutInternal() throws IOException {
    }

    protected void handlePostInternal() throws IOException {
    }

    protected void handleGetInternal() {
    }

    protected void handleDeleteInternal() {
    }
}
