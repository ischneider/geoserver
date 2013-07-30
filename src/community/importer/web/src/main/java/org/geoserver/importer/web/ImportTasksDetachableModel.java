package org.geoserver.importer.web;

import java.util.List;

import org.apache.wicket.model.LoadableDetachableModel;
import org.apache.wicket.model.Model;
import org.geoserver.importer.ImportContext;
import org.geoserver.importer.ImportTask;

public class ImportTasksDetachableModel extends LoadableDetachableModel<List<ImportTask>> {

    long id;

    public ImportTasksDetachableModel(ImportContext imp) {
        this(imp.getId());
    }

    public ImportTasksDetachableModel(long id) {
        this.id = id;
    }

    @Override
    protected List<ImportTask> load() {
        return ImporterWebUtils.importer().getContext(id).getTasks();
    }
}
