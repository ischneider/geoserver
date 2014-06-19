/* Copyright (c) 2001 - 2013 OpenPlans - www.openplans.org. All rights reserved.
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.importer.format;

import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.Point;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.NoSuchElementException;
import org.geotools.data.FeatureReader;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;

public class KMLRawFeatureReader implements FeatureReader<SimpleFeatureType, SimpleFeature> {

    private final InputStream inputStream;

    private final KMLRawReader reader;

    private final SimpleFeatureType featureType;

    private SimpleFeature next;

    private final Class<?> filterGeomType;

    public KMLRawFeatureReader(InputStream inputStream, SimpleFeatureType featureType) {
        this.inputStream = inputStream;
        this.featureType = featureType;
        reader = KMLRawReader.buildFeatureParser(inputStream, featureType);
        Class<?> geomType = featureType.getGeometryDescriptor().getType().getBinding();
        filterGeomType = geomType != Geometry.class ? geomType : null;
    }

    public void setLenientParsing(boolean lenient) {
        reader.setLenientParsing(lenient);
    }

    public List<String> getWarnings() {
        return reader.getWarnings();
    }

    @Override
    public SimpleFeatureType getFeatureType() {
        return featureType;
    }

    @Override
    public SimpleFeature next() throws IOException, IllegalArgumentException,
            NoSuchElementException {
        if (next == null) {
            throw new NoSuchElementException();
        }
        SimpleFeature f = next;
        next = null;
        return f;
    }

    @Override
    public boolean hasNext() throws IOException {
        if (next == null) {
            if (filterGeomType == null) {
                next = (SimpleFeature) reader.parse();
            } else {
                next = nextByGeomType();
            }
        }
        return next != null;
    }

    private SimpleFeature nextByGeomType() throws IOException {
        SimpleFeature f = null;
        while (true) {
            f = (SimpleFeature) reader.parse();
            if (f == null) {
                break;
            }
            // if geom is null and type is Point
            // or if the geom is an instance of the filter
            // take this feature
            Object geom = f.getDefaultGeometry();
            if (geom == null && filterGeomType == Point.class ||
                filterGeomType.isInstance(f.getDefaultGeometry())) {
                break;
            }
        }
        return f;
    }

    @Override
    public void close() throws IOException {
        inputStream.close();
    }

}
