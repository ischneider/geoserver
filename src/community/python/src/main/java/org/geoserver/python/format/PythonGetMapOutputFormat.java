package org.geoserver.python.format;

import java.io.IOException;

import java.util.Collections;
import java.util.Set;

import org.geoserver.platform.ServiceException;
import org.geoserver.wms.GetMapOutputFormat;
import org.geoserver.wms.MapProducerCapabilities;
import org.geoserver.wms.WMSMapContent;
import org.geoserver.wms.WebMap;

public class PythonGetMapOutputFormat implements GetMapOutputFormat {

    PythonVectorFormatAdapter adapter;
    
    public PythonGetMapOutputFormat(PythonVectorFormatAdapter adapter) {
        this.adapter = adapter;
    }
    
    public String getMimeType() {
        return adapter.getMimeType();
    }
    
    public Set<String> getOutputFormatNames() {
        return Collections.singleton(adapter.getName());
    }
    
    public WebMap produceMap(WMSMapContent mapContent) throws ServiceException, IOException {
        return new PythonWebMap(mapContent, adapter);
    }

    public MapProducerCapabilities getCapabilities(String format) {
        return new MapProducerCapabilities(false, false, false, true, null);
    }
}
