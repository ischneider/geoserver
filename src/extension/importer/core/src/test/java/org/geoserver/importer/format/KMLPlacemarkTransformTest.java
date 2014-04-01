/* Copyright (c) 2001 - 2013 OpenPlans - www.openplans.org. All rights reserved.
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.importer.format;

import java.util.ArrayList;
import java.util.List;

import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.kml.Folder;
import org.opengis.feature.simple.SimpleFeature;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.Point;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class KMLPlacemarkTransformTest {
    private KMLPlacemarkTransform kmlPlacemarkTransform;

    @Before
    public void setup() {
        kmlPlacemarkTransform = new KMLPlacemarkTransform(KMLTestData.transformedType());
    }

    @Test
    public void testGeometry() throws Exception {
        SimpleFeatureBuilder fb = new SimpleFeatureBuilder(KMLTestData.origType());
        GeometryFactory gf = new GeometryFactory();
        fb.set("Geometry", gf.createPoint(new Coordinate(3d, 4d)));
        SimpleFeature feature = fb.buildFeature("testgeometry");
        assertEquals("Unexpected Geometry class", Point.class, feature.getAttribute("Geometry")
                .getClass());
        assertEquals("Unexpected default geometry", Point.class, feature.getDefaultGeometry()
                .getClass());
        SimpleFeature result = kmlPlacemarkTransform.convertFeature(feature);
        assertEquals("Invalid Geometry class", Point.class, result.getAttribute("Geometry")
                .getClass());
        assertEquals("Unexpected default geometry", Point.class, feature.getDefaultGeometry()
                .getClass());
    }

    @Test
    public void testLookAtProperty() throws Exception {
        SimpleFeatureBuilder fb = new SimpleFeatureBuilder(KMLTestData.origType());
        GeometryFactory gf = new GeometryFactory();
        Coordinate c = new Coordinate(3d, 4d);
        fb.set("LookAt", gf.createPoint(c));
        SimpleFeature feature = fb.buildFeature("testlookat");
        assertEquals("Unexpected LookAt attribute class", Point.class,
                feature.getAttribute("LookAt").getClass());
        SimpleFeature result = kmlPlacemarkTransform.convertFeature(feature);
        assertEquals("Invalid LookAt attribute class", Point.class, result.getAttribute("LookAt")
                .getClass());
    }

    @Test
    public void testFolders() throws Exception {
        SimpleFeatureBuilder fb = new SimpleFeatureBuilder(KMLTestData.origType());
        List<Folder> folders = new ArrayList<Folder>(2);
        folders.add(new Folder("foo"));
        folders.add(new Folder("bar"));
        fb.featureUserData("Folder", folders);
        SimpleFeature feature = fb.buildFeature("testFolders");
        SimpleFeature newFeature = kmlPlacemarkTransform.convertFeature(feature);
        assertEquals("foo -> bar", newFeature.getAttribute("Folder"));
    }


}
