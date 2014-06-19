/* Copyright (c) 2001 - 2013 OpenPlans - www.openplans.org. All rights reserved.
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.importer.format;

import com.vividsolutions.jts.geom.Geometry;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.geoserver.importer.format.KMLFileFormat.KML_CRS;
import static org.geoserver.importer.format.KMLFileFormat.KML_SRS;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.kml.NetworkLink;
import org.geotools.kml.StyleMap;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.AttributeDescriptor;

/**
 * Support reading from a KML file. Applies a union transformation
 * to make everything uniform. Optionally supports one FeatureType per geometry.
 */
public class KMLFeatureTypeParser {

    private final String typeName;
    private List<SimpleFeatureType> featureTypes;
    private Collection<NetworkLink> networkLinks = new ArrayList<NetworkLink>(3);
    private KMLRawReader reader;
    private final boolean collateByGeometry;
    private final SimpleFeatureTypeBuilder ftb = new SimpleFeatureTypeBuilder();
    private boolean lenient = false;

    KMLFeatureTypeParser(String typeName, boolean collateByGeometry) {
        this.typeName = typeName;
        this.collateByGeometry = collateByGeometry;
    }

    public void setLenientParsing(boolean lenient) {
        this.lenient = lenient;
    }

    public static KMLFeatureTypeParser unionFeatureTypeParser(String typeName) {
        return new KMLFeatureTypeParser(typeName, false);
    }

    public static KMLFeatureTypeParser byGeometryTypeParser(String typeName) {
        return new KMLFeatureTypeParser(typeName, true);
    }

    public List<SimpleFeatureType> getFeatureTypes() {
        return featureTypes;
    }

    public Collection<NetworkLink> getNetworkLinks() {
        return networkLinks;
    }

    public StyleMap getStyleMap() {
        return reader.getStyleMap();
    }

    public void parse(File file) throws IOException {
        InputStream inputStream = null;
        try {
            inputStream = new FileInputStream(file);
            parse(inputStream);
        } finally {
            if (inputStream != null) {
                inputStream.close();
            }
        }
    }

    public void parse(InputStream in) throws IOException {
        reader = KMLRawReader.buildFullParser(in);
        reader.setLenientParsing(lenient);
        try {
            parseInternal(typeName);
        } finally {
            reader.close();
        }
    }

    SimpleFeatureType unionFeatureTypes(SimpleFeatureType a, SimpleFeatureType b) {
        if (a == null) {
            return b;
        }
        ftb.init(a);
        List<AttributeDescriptor> attributeDescriptors = a.getAttributeDescriptors();
        Set<String> attrNames = new HashSet<String>(attributeDescriptors.size());
        for (AttributeDescriptor ad : attributeDescriptors) {
            attrNames.add(ad.getLocalName());
        }
        for (AttributeDescriptor ad : b.getAttributeDescriptors()) {
            if (!attrNames.contains(ad.getLocalName())) {
                ftb.add(ad);
            }
        }
        return ftb.buildFeatureType();
    }

    SimpleFeatureType convertParsedFeatureType(SimpleFeatureType ft, String name,
            Set<String> untypedAttributes) {
        SimpleFeatureType transformedType = convertFeatureType(ft);
        ftb.init(transformedType);
        Set<String> existringAttrNames = new HashSet<String>();
        for (AttributeDescriptor ad : ft.getAttributeDescriptors()) {
            existringAttrNames.add(ad.getLocalName());
        }
        for (String attr : untypedAttributes) {
            if (!existringAttrNames.contains(attr)) {
                ftb.add(attr, String.class);
            }
        }
        ftb.setName(name);
        ftb.setCRS(KML_CRS);
        ftb.setSRS(KML_SRS);
        return ftb.buildFeatureType();
    }

    SimpleFeatureType convertFeatureType(SimpleFeatureType oldFeatureType) {
        ftb.setCRS(KMLFileFormat.KML_CRS);
        ftb.add("Geometry", Geometry.class);
        ftb.setDefaultGeometry("Geometry");
        List<AttributeDescriptor> attributeDescriptors = oldFeatureType.getAttributeDescriptors();
        for (AttributeDescriptor attributeDescriptor : attributeDescriptors) {
            String localName = attributeDescriptor.getLocalName();
            if (!"Geometry".equals(localName)) {
                ftb.add(attributeDescriptor);
            }
        }
        ftb.setName(oldFeatureType.getName());
        ftb.setDescription(oldFeatureType.getDescription());
        // not sure how we'd use these but more importantly they seem
        // to cause problems in the H2 database with a duplicate index error
        if (oldFeatureType.getDescriptor("LookAt") != null) {
            ftb.remove("LookAt");
        }
        if (oldFeatureType.getDescriptor("Region") != null) {
            ftb.remove("Region");
        }
        // change this back to String, our reader will modify the feature
        // it comes from the parser as a URI
        if (oldFeatureType.getDescriptor("Style") != null) {
            ftb.remove("Style");
            ftb.add("Style", String.class);
        }
        ftb.add("Folder", String.class);
        // tell the description field to be big
        SimpleFeatureType ft = ftb.buildFeatureType();
        return ft;
    }

    void parseInternal(String typeName)
            throws IOException {
        Set<String> untypedAttributes = new HashSet<String>();
        List<String> schemaNames = new ArrayList<String>();
        List<SimpleFeatureType> schemas = new ArrayList<SimpleFeatureType>();
        Set<Class> geometryTypes = new HashSet<Class>();
        SimpleFeatureType aggregateFeatureType = null;
        Object object;
        while ((object = reader.parse()) != null) {
            if (object instanceof SimpleFeature) {
                SimpleFeature feature = (SimpleFeature) object;
                SimpleFeatureType ft = feature.getFeatureType();
                aggregateFeatureType = unionFeatureTypes(aggregateFeatureType, ft);
                Map<Object, Object> userData = feature.getUserData();
                @SuppressWarnings("unchecked")
                Map<String, Object> untypedData = (Map<String, Object>) userData
                        .get("UntypedExtendedData");
                if (untypedData != null) {
                    untypedAttributes.addAll(untypedData.keySet());
                }
                Geometry geom = (Geometry) feature.getDefaultGeometry();
                if (geom != null) {
                    geometryTypes.add( geom.getClass() );
                }
            } else if (object instanceof SimpleFeatureType) {
                SimpleFeatureType schema = (SimpleFeatureType) object;
                schemas.add(schema);
                schemaNames.add(schema.getName().getLocalPart());
            } else if (object instanceof NetworkLink) {
                networkLinks.add((NetworkLink) object);
            }
        }
        SimpleFeatureType featureType = aggregateFeatureType;
        for (SimpleFeatureType schema : schemas) {
            featureType = unionFeatureTypes(featureType, schema);
        }
        if (featureType != null) {
            featureType = convertParsedFeatureType(featureType, typeName, untypedAttributes);
            if (!schemaNames.isEmpty()) {
                Map<Object, Object> userData = featureType.getUserData();
                userData.put("schemanames", schemaNames);
            }
            if (collateByGeometry && !geometryTypes.isEmpty()) {
                featureTypes = collateByGeometry(featureType, geometryTypes);
            } else {
                featureTypes = Collections.singletonList(featureType);
            }
        } else {
            featureTypes = Collections.emptyList();
        }
    }

    List<SimpleFeatureType> collateByGeometry(SimpleFeatureType featureType, Set<Class> geometryTypes) {
        List<SimpleFeatureType> collated = new ArrayList<SimpleFeatureType>(geometryTypes.size());
        for (Class geometryType: geometryTypes) {
            ftb.init(featureType);
            ftb.setName(featureType.getTypeName() + geometryType.getSimpleName());
            ftb.remove("Geometry");
            ftb.add("Geometry", geometryType);
            collated.add(ftb.buildFeatureType());
        }
        return collated;
    }
}
