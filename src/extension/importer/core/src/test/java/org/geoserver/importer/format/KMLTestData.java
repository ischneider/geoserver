/* Copyright (c) 2001 - 2013 OpenPlans - www.openplans.org. All rights reserved.
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.importer.format;

import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.LinearRing;
import com.vividsolutions.jts.geom.Point;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.styling.FeatureTypeStyle;
import org.opengis.feature.simple.SimpleFeatureType;

class KMLTestData {

    public static SimpleFeatureType origType() {
        SimpleFeatureTypeBuilder origBuilder = new SimpleFeatureTypeBuilder();
        origBuilder.setSRS("EPSG:4326");
        origBuilder.setName("origtype");
        origBuilder.add("name", String.class);
        origBuilder.add("description", String.class);
        origBuilder.add("LookAt", Point.class);
        origBuilder.add("Region", LinearRing.class);
        origBuilder.add("Style", FeatureTypeStyle.class);
        origBuilder.add("Geometry", Geometry.class);
        origBuilder.setDefaultGeometry("Geometry");
        return origBuilder.buildFeatureType();
    }

    public static SimpleFeatureType transformedType() {
        SimpleFeatureTypeBuilder transformedBuilder = new SimpleFeatureTypeBuilder();
        transformedBuilder.setSRS("EPSG:4326");
        transformedBuilder.setName("transformedtype");
        transformedBuilder.add("name", String.class);
        transformedBuilder.add("description", String.class);
        transformedBuilder.add("LookAt", Point.class);
        transformedBuilder.add("Region", LinearRing.class);
        transformedBuilder.add("Style", String.class);
        transformedBuilder.add("Geometry", Geometry.class);
        transformedBuilder.setDefaultGeometry("Geometry");
        transformedBuilder.add("Folder", String.class);
        return transformedBuilder.buildFeatureType();
    }
}
