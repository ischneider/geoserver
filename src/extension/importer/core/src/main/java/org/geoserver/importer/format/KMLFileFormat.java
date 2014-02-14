/* Copyright (c) 2001 - 2013 OpenPlans - www.openplans.org. All rights reserved.
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.importer.format;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.zip.ZipFile;
import org.apache.commons.io.FilenameUtils;
import org.geoserver.catalog.AttributeTypeInfo;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.CatalogBuilder;
import org.geoserver.catalog.CatalogFactory;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.MetadataMap;
import org.geoserver.catalog.ResourceInfo;
import org.geoserver.catalog.StoreInfo;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.importer.FileData;
import org.geoserver.importer.ImportData;
import org.geoserver.importer.ImportTask;
import org.geoserver.importer.VFSWorker;
import org.geoserver.importer.VectorFormat;
import org.geoserver.importer.job.ProgressMonitor;
import org.geoserver.importer.transform.KMLPlacemarkTransform;
import org.geotools.data.FeatureReader;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.AttributeDescriptor;
import org.opengis.feature.type.FeatureType;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

/**
 * Implementation notes: to deal with KMZ correctly, the KMZ must be unzipped
 * into a directory with a name ending in '.kmz'. {@link #unpack(File)} will
 * do this, deleting the original kmz.
 */
public class KMLFileFormat extends VectorFormat {

    /** serialVersionUID */
    private static final long serialVersionUID = 1L;

    public static String KML_SRS = "EPSG:4326";

    public static CoordinateReferenceSystem KML_CRS;

    private static KMLPlacemarkTransform kmlTransform = new KMLPlacemarkTransform();

    private static ReferencedEnvelope EMPTY_BOUNDS = new ReferencedEnvelope();

    static {
        try {
            KML_CRS = CRS.decode(KML_SRS);
        } catch (Exception e) {
            throw new RuntimeException("Could not decode: EPSG:4326", e);
        }
        EMPTY_BOUNDS.setToNull();
    }

    @SuppressWarnings("rawtypes")
    @Override
    public FeatureReader read(ImportData data, ImportTask task) throws IOException {
        File file = getFileFromData(data);

        // we need to get the feature type, to use for the particular parse through the file
        // since we put it on the metadata from the list method, we first check if that's still available
        SimpleFeatureType ft = (SimpleFeatureType) task.getMetadata().get(FeatureType.class);
        if (ft == null) {
            // if the type is not available, we can generate one from the resource
            // we aren't able to ask for the feature type from the resource directly,
            // because we don't have a backing store
            FeatureTypeInfo fti = (FeatureTypeInfo) task.getLayer().getResource();
            SimpleFeatureTypeBuilder ftb = new SimpleFeatureTypeBuilder();
            ftb.setName(fti.getName());
            List<AttributeTypeInfo> attributes = fti.getAttributes();
            for (AttributeTypeInfo attr : attributes) {
                ftb.add(attr.getName(), attr.getBinding());
            }
            ft = ftb.buildFeatureType();
            MetadataMap metadata = fti.getMetadata();
            if (metadata.containsKey("importschemanames")) {
                Map<Object, Object> userData = ft.getUserData();
                userData.put("schemanames", metadata.get("importschemanames"));
            }
        }
        return read(ft, file);
    }

    public FeatureReader<SimpleFeatureType, SimpleFeature> read(SimpleFeatureType featureType,
            File file) {
        try {
            return new KMLTransformingFeatureReader(featureType, new FileInputStream(file));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public FeatureReader<SimpleFeatureType, SimpleFeature> read(SimpleFeatureType featureType,
            InputStream inputStream) {
        return new KMLTransformingFeatureReader(featureType, inputStream);
    }

    @Override
    public void dispose(@SuppressWarnings("rawtypes") FeatureReader reader, ImportTask task)
            throws IOException {
        reader.close();
    }

    @Override
    public int getFeatureCount(ImportData data, ImportTask task) throws IOException {
        // we don't have a fast way to get the count
        // instead of parsing through the entire file
        return -1;
    }

    @Override
    public String getName() {
        return "KML";
    }

    @Override
    public boolean canRead(ImportData data) throws IOException {
        File file = getFileFromData(data);
        String extension = FilenameUtils.getExtension(file.getName());
        boolean canRead = false;
        if ("kml".equalsIgnoreCase(extension)) {
            canRead = file.canRead();
        } else if ("kmz".equalsIgnoreCase(extension)) {
            try {
                ZipFile zipFile = new ZipFile(file);
                zipFile.close();
                canRead = true;
            } catch (Exception ex) {
                // not zip file
            }
        }
        return canRead;
    }

    private File getFileFromData(ImportData data) {
        assert data instanceof FileData;
        FileData fileData = (FileData) data;
        File file = fileData.getFile();
        return file;
    }

    @Override
    public StoreInfo createStore(ImportData data, WorkspaceInfo workspace, Catalog catalog)
            throws IOException {
        // null means no direct store imports can be performed
        return null;
    }

    public Collection<SimpleFeatureType> parseFeatureTypes(String typeName, File file)
            throws IOException {
        InputStream inputStream = null;
        try {
            inputStream = new FileInputStream(file);
            return parseFeatureTypes(typeName, inputStream);
        } finally {
            if (inputStream != null) {
                inputStream.close();
            }
        }
    }

    private SimpleFeatureType unionFeatureTypes(SimpleFeatureType a, SimpleFeatureType b) {
        if (a == null) {
            return b;
        }
        SimpleFeatureTypeBuilder ftb = new SimpleFeatureTypeBuilder();
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

    public SimpleFeatureType convertParsedFeatureType(SimpleFeatureType ft, String name,
            Set<String> untypedAttributes) {
        SimpleFeatureType transformedType = kmlTransform.convertFeatureType(ft);
        SimpleFeatureTypeBuilder ftb = new SimpleFeatureTypeBuilder();
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

    public List<SimpleFeatureType> parseFeatureTypes(String typeName, InputStream inputStream)
            throws IOException {
        KMLRawReader reader = new KMLRawReader(inputStream,
                KMLRawReader.ReadType.SCHEMA_AND_FEATURES);
        Set<String> untypedAttributes = new HashSet<String>();
        List<String> schemaNames = new ArrayList<String>();
        List<SimpleFeatureType> schemas = new ArrayList<SimpleFeatureType>();
        SimpleFeatureType aggregateFeatureType = null;
        for (Object object : reader) {
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
            } else if (object instanceof SimpleFeatureType) {
                SimpleFeatureType schema = (SimpleFeatureType) object;
                schemas.add(schema);
                schemaNames.add(schema.getName().getLocalPart());
            }
        }
        if (aggregateFeatureType == null && schemas.isEmpty()) {
            throw new IllegalArgumentException("No features found");
        }
        SimpleFeatureType featureType = aggregateFeatureType;
        for (SimpleFeatureType schema : schemas) {
            featureType = unionFeatureTypes(featureType, schema);
        }
        featureType = convertParsedFeatureType(featureType, typeName, untypedAttributes);
        if (!schemaNames.isEmpty()) {
            Map<Object, Object> userData = featureType.getUserData();
            userData.put("schemanames", schemaNames);
        }
        return Collections.singletonList(featureType);
    }

    @Override
    public List<ImportTask> list(ImportData data, Catalog catalog, ProgressMonitor monitor)
            throws IOException {
        File file = getFileFromData(data);
        CatalogBuilder cb = new CatalogBuilder(catalog);
        String baseName = typeNameFromFile(file);
        CatalogFactory factory = catalog.getFactory();

        Collection<SimpleFeatureType> featureTypes = parseFeatureTypes(baseName, file);
        List<ImportTask> result = new ArrayList<ImportTask>(featureTypes.size());
        for (SimpleFeatureType featureType : featureTypes) {
            String name = featureType.getName().getLocalPart();
            FeatureTypeInfo ftinfo = factory.createFeatureType();
            ftinfo.setEnabled(true);
            ftinfo.setNativeName(name);
            ftinfo.setName(name);
            ftinfo.setTitle(name);
            ftinfo.setNamespace(catalog.getDefaultNamespace());
            List<AttributeTypeInfo> attributes = ftinfo.getAttributes();
            for (AttributeDescriptor ad : featureType.getAttributeDescriptors()) {
                AttributeTypeInfo att = factory.createAttribute();
                att.setName(ad.getLocalName());
                att.setBinding(ad.getType().getBinding());
                attributes.add(att);
            }

            LayerInfo layer = cb.buildLayer((ResourceInfo) ftinfo);
            ResourceInfo resource = layer.getResource();
            resource.setSRS(KML_SRS);
            resource.setNativeCRS(KML_CRS);
            resource.setNativeBoundingBox(EMPTY_BOUNDS);
            resource.setLatLonBoundingBox(EMPTY_BOUNDS);
            resource.getMetadata().put("recalculate-bounds", Boolean.TRUE);

            Map<Object, Object> userData = featureType.getUserData();
            if (userData.containsKey("schemanames")) {
                MetadataMap metadata = resource.getMetadata();
                metadata.put("importschemanames", (Serializable) userData.get("schemanames"));
            }

            ImportTask task = new ImportTask(data);
            task.setLayer(layer);
            task.getMetadata().put(FeatureType.class, featureType);
            result.add(task);
        }
        return Collections.unmodifiableList(result);
    }

    private String typeNameFromFile(File file) {
        String parentExt = FilenameUtils.getExtension(file.getParent());
        // if the doc.kml is in a directory ending w/ kmz, take that directory name
        // so the featuretype doesn't end up as 'doc'.
        if ("kmz".equalsIgnoreCase(parentExt)) {
            file = file.getParentFile();
        }
        return FilenameUtils.getBaseName(file.getName());
    }

    /**
     * If needed, unpack a KMZ to a directory of the same name, deleting the
     * original KMZ.
     * @param file
     * @throws IOException
     */
    @Override
    public void unpack(File file) throws IOException {
        String extension = FilenameUtils.getExtension(file.getName());
        if ("kmz".equalsIgnoreCase(extension)) {
            File parent = file.getCanonicalFile().getParentFile();
            // prefix the temp file or VFSWorker will get tripped up
            File tmp = new File(parent, "tmp" + file.getName());
            if (!file.renameTo(tmp)) {
                throw new IOException("Unable to create temp file for unpacking kmz");
            }
            file.mkdir();
            VFSWorker vfs = new VFSWorker();
            vfs.extractTo(tmp, file);
            tmp.delete();
        }
    }
}
