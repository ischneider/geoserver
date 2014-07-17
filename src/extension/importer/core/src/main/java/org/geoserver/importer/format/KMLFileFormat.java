/* Copyright (c) 2001 - 2013 OpenPlans - www.openplans.org. All rights reserved.
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.importer.format;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.apache.commons.io.FileUtils;

import java.util.zip.ZipFile;
import javax.servlet.ServletContext;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.geoserver.catalog.AttributeTypeInfo;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.CatalogBuilder;
import org.geoserver.catalog.CatalogFactory;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.catalog.LayerInfo;
import org.geoserver.catalog.MetadataMap;
import org.geoserver.catalog.ProjectionPolicy;
import org.geoserver.catalog.ResourceInfo;
import org.geoserver.catalog.StoreInfo;
import org.geoserver.catalog.StyleInfo;
import org.geoserver.catalog.WorkspaceInfo;
import org.geoserver.config.GeoServerDataDirectory;
import org.geotools.data.FeatureReader;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.geoserver.importer.FileData;
import org.geoserver.importer.ImportData;
import org.geoserver.importer.ImportTask;
import org.geoserver.importer.Importer;
import org.geoserver.importer.StyleGenerator;
import org.geoserver.importer.VFSWorker;
import org.geoserver.importer.VectorFormat;
import org.geoserver.importer.job.ProgressMonitor;
import org.geoserver.platform.GeoServerExtensions;
import org.geotools.kml.StyleMap;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.AttributeDescriptor;
import org.opengis.feature.type.FeatureType;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

/**
 * Implementation notes: to deal with KMZ correctly, the KMZ must be unzipped
 * into a directory with a name ending in '.kmz' or '.kml'. {@link #unpack(File)} will
 * do this, deleting the original kmz (or kml if packaged as kmz but misnamed).
 */
public class KMLFileFormat extends VectorFormat {

    /** serialVersionUID */
    private static final long serialVersionUID = 1L;

    public static String KML_SRS = "EPSG:4326";

    public static CoordinateReferenceSystem KML_CRS;

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
    public FeatureReader read(ImportData data, final ImportTask task) throws IOException {
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
        KMLTransformingFeatureReader reader = new KMLTransformingFeatureReader(ft, new FileInputStream(file)) {
            {
                reader.setLenientParsing(task.isAllowIngestErrors());
            }

            @Override
            public SimpleFeature next() {
                SimpleFeature feat = super.next();
                for (int i = 0; i < reader.getWarnings().size(); i++) {
                    task.addMessage(Level.WARNING, reader.getWarnings().get(i));
                }
                reader.getWarnings().clear();
                return feat;
            }

        };
        File kmzDir = getKmzDirectory(task);
        List<String> paths = (List<String>) (kmzDir == null ? Collections.emptyList() : getResourcePaths(kmzDir));
        File stylePath = getStylePath(task);
        GeoServerDataDirectory dataDir = GeoServerExtensions.bean(GeoServerDataDirectory.class);
        ServletContext context = GeoServerExtensions.bean(ServletContext.class);
        String contextPath = "/geoserver";
        try {
            contextPath = context.getContextPath();
        } catch (AbstractMethodError ame) {
            // mock context is old
        }
        String relativePath = stylePath.toURI().getPath().replace(dataDir.root().toURI().getPath(), "");
        reader.setRewritePaths(contextPath + "/" + relativePath + "/", paths);
        return reader;
    }

    static List<String> getResourcePaths(File dir) {
        List<String> paths = new ArrayList<String>();
        Collection<File> files = FileUtils.listFiles(dir, FileFilterUtils.fileFileFilter(), FileFilterUtils.trueFileFilter());
        String parent = dir.toURI().getPath();
        for (File f: files) {
            if (!f.getName().toLowerCase().endsWith(".kml")) {
                paths.add(f.toURI().getPath().replace(parent, ""));
            }
        }
        return paths;
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
            canRead = isZip(file);
        }
        return canRead;
    }
    
    private boolean isZip(File file) {
        boolean zip = false;
        try {
            ZipFile zipFile = new ZipFile(file);
            zipFile.close();
            zip = true;
        } catch (Exception ex) {
            // not zip file
        }
        return zip;
    }

    private static File getFileFromData(ImportData data) {
        assert data instanceof FileData;
        return ((FileData) data).getFile();
    }

    @Override
    public StoreInfo createStore(ImportData data, WorkspaceInfo workspace, Catalog catalog)
            throws IOException {
        // null means no direct store imports can be performed
        return null;
    }

    @Override
    public List<ImportTask> list(ImportData data, Catalog catalog, ProgressMonitor monitor)
            throws IOException {
        File file = getFileFromData(data);
        CatalogBuilder cb = new CatalogBuilder(catalog);
        String baseName = typeNameFromFile(file);
        CatalogFactory factory = catalog.getFactory();

        KMLFeatureTypeParser parser = data.getOptions().contains("kml:bygeometry") ?
                KMLFeatureTypeParser.byGeometryTypeParser(baseName) :
                KMLFeatureTypeParser.unionFeatureTypeParser(baseName);
        boolean lenient = data.getOptions().contains("kml:lenient");
        parser.setLenientParsing(lenient);
        parser.parse(file);
        Collection<SimpleFeatureType> featureTypes = parser.getFeatureTypes();
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
            resource.setProjectionPolicy(ProjectionPolicy.FORCE_DECLARED);
            resource.getMetadata().put("recalculate-bounds", Boolean.TRUE);

            Map<Object, Object> userData = featureType.getUserData();
            if (userData.containsKey("schemanames")) {
                MetadataMap metadata = resource.getMetadata();
                metadata.put("importschemanames", (Serializable) userData.get("schemanames"));
            }

            ImportTask task = new ImportTask(data);
            task.setAllowIngestErrors(lenient);
            task.setLayer(layer);
            task.getMetadata().put(FeatureType.class, featureType);
            task.getMetadata().put(StyleMap.class, parser.getStyleMap());
            result.add(task);
        }
        return Collections.unmodifiableList(result);
    }

    private static String typeNameFromFile(File file) {
        String parentExt = FilenameUtils.getExtension(file.getParent());
        // if the doc.kml is in a directory ending w/ kmz or kml, take that directory name
        // so the featuretype doesn't end up as 'doc'.
        if ("kmz".equalsIgnoreCase(parentExt) || "kml".equalsIgnoreCase(parentExt)) {
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
        // apparently, some people name a kmz with a kml extension, so check if zip
        if ("kmz".equalsIgnoreCase(extension) || isZip(file)) {
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

    public static File getStylePath(ImportTask task) {
        GeoServerDataDirectory dataDir = GeoServerExtensions.bean(GeoServerDataDirectory.class);
        File styleDir;
        try {
            styleDir = dataDir.findStyleDir();
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        String sourceName = typeNameFromFile(getFileFromData(task.getData()));
        String stylePath = task.getStore().getWorkspace().getName() + "_" + sourceName;
        return new File(styleDir, stylePath);
    }
    
    private StyleInfo findStyleInfo(ImportData data, List<ImportTask> tasks) {
        StyleInfo style = null;
        for (ImportTask t: tasks) {
            if (data.equals(t.getData())) {
                style = t.getLayer().getDefaultStyle();
                if (style != null) {
                    break;
                }
            }
        }
        return style;
    }

    @Override
    public void prepare(ImportTask task) {
        // compute style directory - this could be done earlier in the game, but
        // safer at this point during actual ingestion

        // we know this will be an indirect task to a catalog will exist
        Catalog catalog = (Catalog) GeoServerExtensions.bean("catalog");

        // because we want one single style and it's possible a task either
        // comes from a single kml/kmz or is the result of collation, search
        // for an existing match in the context
        StyleInfo style = findStyleInfo(task.getData(), task.getContext().getTasks());
        if (style == null) {
            // make a style independent of the layer name in case we're using collated by geometry
            String name = StyleGenerator.findUniqueStyleName(catalog, task.getStore().getWorkspace().getName(),
                    typeNameFromFile(getFileFromData(task.getData())));
            style = catalog.getFactory().createStyle();
            style.setName(name);
            style.setFilename(name + ".sld");
        }
        task.getLayer().setDefaultStyle(style);
    }

    @Override
    public void finish(ImportTask task) {
        File resources = getStylePath(task);
        // if there are multiple tasks related to collation by geometry,
        // ensure this step only occurs once
        if (resources.exists()) {
            return;
        }
        resources.mkdirs();

        // deploy SLD
        KMLSLDAssembler sld = new KMLSLDAssembler();
        sld.setSLDName(task.getLayer().getName());
        // @todo go back and regenerate if missing?
        StyleMap styles = (StyleMap) task.getMetadata().get(StyleMap.class);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream stream = new PrintStream(buffer);
        // use the name of the directory to rewrite relative style URIs
        File directory = getKmzDirectory(task);
        sld.write(stream, styles, resources.getName(), directory, resources);
        Catalog catalog = (Catalog) GeoServerExtensions.bean("catalog");
        StyleInfo style = task.getLayer().getDefaultStyle();
        try {
            catalog.getResourcePool().writeStyle(style, new ByteArrayInputStream(buffer.toByteArray()));
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }

        // deploy any referenced resources
        if (directory != null) {
            File[] f = directory.listFiles();
            for (int i = 0; i < f.length; i++) {
                File file = f[i];
                if (file.getName().endsWith(".kml")) {
                    continue;
                }
                try {
                    if (file.isDirectory()) {
                        FileUtils.copyDirectoryToDirectory(file, resources);
                    } else {
                        FileUtils.copyFileToDirectory(file, resources);
                    }
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
    }

    private File getKmzDirectory(ImportTask task) {
        FileData data = ((FileData) task.getData());
        File directory = data.getFile().getParentFile();
        return directory.getName().endsWith(".kmz") ? directory : null;
    }

}
