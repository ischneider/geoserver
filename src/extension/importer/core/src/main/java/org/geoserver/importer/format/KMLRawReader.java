/* Copyright (c) 2001 - 2013 OpenPlans - www.openplans.org. All rights reserved.
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.importer.format;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.xml.namespace.QName;

import org.geotools.kml.v22.KML;
import org.geotools.kml.StyleMap;
import org.geotools.kml.v22.KMLConfiguration;
import org.geotools.xml.PullParser;
import org.opengis.feature.simple.SimpleFeatureType;

/**
 * Wrapper for a PullParser and it's related KML components.
 */
public class KMLRawReader {

    private final KMLConfiguration config;
    private final PullParser parser;
    private final InputStream stream;

    private KMLRawReader(InputStream stream, Object... specs) {
        this.stream = stream;
        this.config = new KMLConfiguration();
        // this will force the parser to only put a URI in the generated
        // placemarks' Style attribute.
        config.setOnlyCollectStyles(true);
        this.parser = new PullParser(config, stream, specs);
    }

    public void setLenientParsing(boolean lenient) {
        this.config.setLenientGeometryParsing(lenient);
    }

    public StyleMap getStyleMap() {
        return config.getStyleMap();
    }

    public List<String> getWarnings() {
        return config.getParseWarnings();
    }

    /**
     * Depending upon how created, return any of FeatureType, Feature, NetworkLink
     * @return non-null object
     * @throws IOException
     */
    public Object parse() throws IOException {
        try {
            return parser.parse();
        } catch (Exception ex) {
            if (ex instanceof IOException) throw (IOException) ex;
            throw new IOException(ex);
        }
    }

    public void close() {
        try {
            stream.close();
        } catch (IOException ex) {
            // dang
        }
    }

    /**
     * Create a KMLRawReader that returns all objects of interest.
     */
    public static KMLRawReader buildFullParser(InputStream stream) {
        return new KMLRawReader(stream, handlerSpecs(null, KML.Placemark, KML.Schema, KML.NetworkLink));
    }

    /**
     * Create a KMLRawReader that returns only Placemarks and optionally those
     * of the provided featureType
     */
    public static KMLRawReader buildFeatureParser(InputStream stream, SimpleFeatureType featureType) {
        return new KMLRawReader(stream, handlerSpecs(featureType, KML.Placemark));
    }

    private static Object[] handlerSpecs(SimpleFeatureType featureType, Object... other) {
        List specs = new ArrayList();
        specs.addAll(Arrays.asList(other));
        if (featureType != null) {
            Map<Object, Object> userData = featureType.getUserData();
            if (userData.containsKey("schemanames")) {
                List<String> names = (List<String>) userData.get("schemanames");
                for (String name : names) {
                    specs.add(new QName(name));
                }
            }
        }
        return specs.toArray();
    }

}
