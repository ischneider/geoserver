/* Copyright (c) 2001 - 2013 OpenPlans - www.openplans.org. All rights reserved.
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.importer.format;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import junit.framework.TestCase;

import org.apache.commons.io.IOUtils;
import org.geoserver.importer.ImporterTestUtils;
import org.geotools.data.FeatureReader;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;

public class KMLFileFormatTest extends TestCase {

    private KMLFileFormat kmlFileFormat;
    static final String DOC_EL = "<kml xmlns=\"http://www.opengis.net/kml/2.2\">";

    @Override
    protected void setUp() throws Exception {
        kmlFileFormat = new KMLFileFormat();
    }

    public void testResourcePaths() throws Exception {
        File dir = ImporterTestUtils.tmpDir();
        new File(dir, "subdir").mkdir();
        new File(dir, "one.png").createNewFile();
        new File(dir, "subdir/two.png").createNewFile();
        List<String> resourcePaths = KMLFileFormat.getResourcePaths(dir);
        assertEquals(Arrays.asList("one.png","subdir/two.png"), resourcePaths);
    }

    public void testUnpack() throws Exception {
        File unpack = ImporterTestUtils.tmpDir();
        File kml = new File(unpack, "kml.kml"); // we don't need a valid file
        kml.createNewFile();
        File kmz = ImporterTestUtils.createKMZ(unpack, "sample", kml.getAbsolutePath());
        kmlFileFormat.unpack(kmz);
        File unpackDir = new File(unpack, "sample.kmz");
        assertTrue(unpackDir.isDirectory());
        assertTrue(new File(unpackDir, "doc.kml").isFile());

        File kmzAsKml = new File(kmz.getAbsolutePath().replace(".kmz", ".kml"));
        kmz.renameTo(kmzAsKml);
        kmlFileFormat.unpack(kml);
        unpackDir = new File(unpack, "sample.kml");
        assertTrue(unpackDir.isDirectory());
        assertTrue(new File(unpackDir, "doc.kml").isFile());
    }

    public void testParseFeatureTypeNoPlacemarks() throws IOException {
        KMLFeatureTypeParser parser = KMLFeatureTypeParser.unionFeatureTypeParser("foo");
        String kmlInput = DOC_EL + "</kml>";
        parser.parse(IOUtils.toInputStream(kmlInput));
        assertTrue(parser.getFeatureTypes().isEmpty());
    }

    private List<SimpleFeatureType> parseUnionFeatureTypes(String name, String input) throws Exception {
        KMLFeatureTypeParser parser = KMLFeatureTypeParser.unionFeatureTypeParser(name);
        parser.parse(IOUtils.toInputStream(input));
        return parser.getFeatureTypes();
    }

    public void testParseFeatureTypeMinimal() throws Exception {
        String kmlInput = DOC_EL + "<Placemark></Placemark></kml>";
        List<SimpleFeatureType> featureTypes = parseUnionFeatureTypes("foo", kmlInput);
        assertEquals("Unexpected number of feature types", 1, featureTypes.size());
        SimpleFeatureType featureType = featureTypes.get(0);
        assertEquals("Unexpected number of feature type attributes", 9,
                featureType.getAttributeCount());
    }

    public void testExtendedUserData() throws Exception {
        String kmlInput = DOC_EL + "<Placemark>" + "<ExtendedData>"
                + "<Data name=\"foo\"><value>bar</value></Data>"
                + "<Data name=\"quux\"><value>morx</value></Data>" + "</ExtendedData>"
                + "</Placemark></kml>";
        List<SimpleFeatureType> featureTypes = parseUnionFeatureTypes("fleem", kmlInput);
        assertEquals("Unexpected number of feature types", 1, featureTypes.size());
        SimpleFeatureType featureType = featureTypes.get(0);
        assertEquals("Unexpected number of feature type attributes", 11,
                featureType.getAttributeCount());
        assertEquals("Invalid attribute descriptor", String.class, featureType.getDescriptor("foo")
                .getType().getBinding());
        assertEquals("Invalid attribute descriptor", String.class, featureType
                .getDescriptor("quux").getType().getBinding());
    }

    public void testReadFeatureWithNameAndDescription() throws Exception {
        String kmlInput = DOC_EL + "<Placemark><name>foo</name><description>bar</description></Placemark></kml>";
        SimpleFeatureType featureType = parseUnionFeatureTypes("foo", kmlInput).get(0);
        FeatureReader<SimpleFeatureType, SimpleFeature> reader = kmlFileFormat.read(featureType,
                IOUtils.toInputStream(kmlInput));
        assertTrue("No features found", reader.hasNext());
        SimpleFeature feature = reader.next();
        assertNotNull("Expecting feature", feature);
        assertEquals("Invalid name attribute", "foo", feature.getAttribute("name"));
        assertEquals("Invalid description attribute", "bar", feature.getAttribute("description"));
    }

    public void testReadFeatureWithUntypedExtendedData() throws Exception {
        String kmlInput = DOC_EL + "<Placemark>" + "<ExtendedData>"
                + "<Data name=\"foo\"><value>bar</value></Data>"
                + "<Data name=\"quux\"><value>morx</value></Data>" + "</ExtendedData>"
                + "</Placemark></kml>";
        SimpleFeatureType featureType = parseUnionFeatureTypes("foo", kmlInput).get(0);
        FeatureReader<SimpleFeatureType, SimpleFeature> reader = kmlFileFormat.read(featureType,
                IOUtils.toInputStream(kmlInput));
        assertTrue("No features found", reader.hasNext());
        SimpleFeature feature = (SimpleFeature) reader.next();
        assertNotNull("Expecting feature", feature);
        assertEquals("Invalid ext attr foo", "bar", feature.getAttribute("foo"));
        assertEquals("Invalid ext attr quux", "morx", feature.getAttribute("quux"));
    }

    public void testReadFeatureWithTypedExtendedData() throws Exception {
        String kmlInput = DOC_EL + "<Schema name=\"myschema\">"
                + "<SimpleField type=\"int\" name=\"foo\"></SimpleField>" + "</Schema>"
                + "<Placemark>" + "<ExtendedData>" + "<SchemaData schemaUrl=\"#myschema\">"
                + "<SimpleData name=\"foo\">42</SimpleData>" + "</SchemaData>" + "</ExtendedData>"
                + "</Placemark></kml>";
        SimpleFeatureType featureType = parseUnionFeatureTypes("foo", kmlInput).get(0);
        FeatureReader<SimpleFeatureType, SimpleFeature> reader = kmlFileFormat.read(featureType,
                IOUtils.toInputStream(kmlInput));
        assertTrue("No features found", reader.hasNext());
        SimpleFeature feature = reader.next();
        assertNotNull("Expecting feature", feature);
        assertEquals("Invalid ext attr foo", 42, feature.getAttribute("foo"));
    }

    public void testMultipleSchemas() throws Exception {
        String kmlInput = DOC_EL + "<Schema name=\"schema1\">"
                + "<SimpleField type=\"int\" name=\"foo\"></SimpleField>" + "</Schema>"
                + "<Schema name=\"schema2\">"
                + "<SimpleField type=\"float\" name=\"bar\"></SimpleField>" + "</Schema>"
                + "<Placemark>" + "<ExtendedData>" + "<SchemaData schemaUrl=\"#schema1\">"
                + "<SimpleData name=\"foo\">42</SimpleData>" + "</SchemaData>"
                + "<SchemaData schemaUrl=\"#schema2\">"
                + "<SimpleData name=\"bar\">4.2</SimpleData>" + "</SchemaData>" + "</ExtendedData>"
                + "</Placemark></kml>";
        List<SimpleFeatureType> featureTypes = parseUnionFeatureTypes("multiple", kmlInput);
        assertEquals("Unexpected number of feature types", 1, featureTypes.size());
        SimpleFeatureType ft = featureTypes.get(0);

        FeatureReader<SimpleFeatureType, SimpleFeature> reader = kmlFileFormat.read(ft,
                IOUtils.toInputStream(kmlInput));
        assertTrue(reader.hasNext());
        SimpleFeature feature1 = reader.next();
        assertNotNull("Expecting feature", feature1);
        assertEquals("Invalid ext attr foo", 42, feature1.getAttribute("foo"));
        assertEquals("Invalid ext attr bar", 4.2f, (Float) feature1.getAttribute("bar"), 0.01);
    }

    public void testTypedAndUntyped() throws Exception {
        String kmlInput = DOC_EL + "<Schema name=\"myschema\">"
                + "<SimpleField type=\"int\" name=\"foo\"></SimpleField>" + "</Schema>"
                + "<Placemark>" + "<ExtendedData>" + "<SchemaData schemaUrl=\"#myschema\">"
                + "<SimpleData name=\"foo\">42</SimpleData>" + "</SchemaData>"
                + "<Data name=\"fleem\"><value>bar</value></Data>"
                + "<Data name=\"quux\"><value>morx</value></Data>" + "</ExtendedData>"
                + "</Placemark></kml>";
        List<SimpleFeatureType> featureTypes = parseUnionFeatureTypes("typed-and-untyped", kmlInput);
        assertEquals("Unexpected number of feature types", 1, featureTypes.size());
        SimpleFeatureType featureType = featureTypes.get(0);
        FeatureReader<SimpleFeatureType, SimpleFeature> reader = kmlFileFormat.read(featureType,
                IOUtils.toInputStream(kmlInput));
        assertTrue(reader.hasNext());
        SimpleFeature feature = reader.next();
        assertNotNull("Expecting feature", feature);
        assertEquals("Invalid ext attr foo", 42, feature.getAttribute("foo"));
        assertEquals("bar", feature.getAttribute("fleem"));
        assertEquals("morx", feature.getAttribute("quux"));
    }

    public void testReadCustomSchema() throws Exception {
        String kmlInput = DOC_EL + "<Schema name=\"myschema\">"
                + "<SimpleField type=\"int\" name=\"foo\"></SimpleField>" + "</Schema>"
                + "<myschema><foo>7</foo></myschema>" + "</kml>";
        List<SimpleFeatureType> featureTypes = parseUnionFeatureTypes("custom-schema", kmlInput);
        assertEquals("Unexpected number of feature types", 1, featureTypes.size());
        SimpleFeatureType featureType = featureTypes.get(0);
        Map<Object, Object> userData = featureType.getUserData();
        List<String> schemaNames = (List<String>) userData.get("schemanames");
        assertEquals(1, schemaNames.size());
        assertEquals("Did not find expected schema name metadata", "myschema", schemaNames.get(0));
        FeatureReader<SimpleFeatureType, SimpleFeature> reader = kmlFileFormat.read(featureType,
                IOUtils.toInputStream(kmlInput));
        assertTrue(reader.hasNext());
        SimpleFeature feature = reader.next();
        assertNotNull("Expecting feature", feature);
        assertEquals("Invalid ext attr foo", 7, feature.getAttribute("foo"));
    }
}
