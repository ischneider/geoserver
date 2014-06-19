/* Copyright (c) 2001 - 2013 OpenPlans - www.openplans.org. All rights reserved.
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.importer.format;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.geotools.data.FeatureReader;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;

public class KMLTransformingFeatureReader implements
        FeatureReader<SimpleFeatureType, SimpleFeature> {

    private final SimpleFeatureType featureType;

    protected final KMLRawFeatureReader reader;

    private final KMLPlacemarkTransform placemarkTransformer;

    public KMLTransformingFeatureReader(SimpleFeatureType featureType, InputStream inputStream) {
        placemarkTransformer = new KMLPlacemarkTransform(featureType);
        this.featureType = featureType;
        this.reader = new KMLRawFeatureReader(inputStream, featureType);
    }

    @Override
    public SimpleFeatureType getFeatureType() {
        return featureType;
    }

    @Override
    public boolean hasNext() {
        try {
            return reader.hasNext();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public SimpleFeature next() {
        SimpleFeature feature;
        try {
            feature = (SimpleFeature) reader.next();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        SimpleFeature transformedFeature = placemarkTransformer.convertFeature(feature);
        return transformedFeature;
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }

    public void setRewritePaths(String prefix, List<String> paths) {
        placemarkTransformer.setRewritePaths(prefix, paths);
    }
}
