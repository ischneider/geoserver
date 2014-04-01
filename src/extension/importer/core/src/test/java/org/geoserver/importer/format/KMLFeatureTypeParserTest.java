/* Copyright (c) 2001 - 2013 OpenPlans - www.openplans.org. All rights reserved.
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.importer.format;

import static junit.framework.TestCase.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import org.junit.Test;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.AttributeDescriptor;

public class KMLFeatureTypeParserTest {

    @Test
    public void testFeatureType() throws Exception {
        KMLFeatureTypeParser parser = new KMLFeatureTypeParser(null, false);

        SimpleFeatureType result = parser.convertFeatureType(KMLTestData.origType());
        // we're stripping these
        assertNull(result.getDescriptor("LookAt"));
        assertNull(result.getDescriptor("Region"));
        assertBinding(result, "Folder", String.class);
        assertBinding(result, "Style", String.class);
    }

    private void assertBinding(SimpleFeatureType ft, String attr, Class<?> expectedBinding) {
        AttributeDescriptor descriptor = ft.getDescriptor(attr);
        assertNotNull("expected " + attr, descriptor);
        Class<?> binding = descriptor.getType().getBinding();
        assertEquals(expectedBinding, binding);
    }
}
