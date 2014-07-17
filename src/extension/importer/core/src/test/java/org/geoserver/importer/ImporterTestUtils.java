/* Copyright (c) 2001 - 2013 OpenPlans - www.openplans.org. All rights reserved.
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.importer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.commons.io.FilenameUtils;

import org.apache.commons.io.IOUtils;
import org.geotools.factory.Hints;

public class ImporterTestUtils {

    public static void setComparisonTolerance() {
        //need to set hint which allows for lax projection lookups to match
        // random wkt to an epsg code
        Hints.putSystemDefault(Hints.COMPARISON_TOLERANCE, 1e-9);
    }

    public static File tmpDir() throws Exception {
        File dir = File.createTempFile("importer", "data", new File("target"));
        dir.delete();
        dir.mkdirs();
        return dir;
    }
    
    public static  File unpack(String path) throws Exception {
        return unpack(path, tmpDir());
    }
    
    public static  File unpack(String path, File dir) throws Exception {
        
        File file = file(path, dir);
        
        new VFSWorker().extractTo(file, dir);
        if (!file.delete()) {
            // fail early as tests will expect it's deleted
            throw new IOException("deletion failed during extraction");
        }
        
        return dir;
    }
    
    public static  File file(String path) throws Exception {
        return file(path, tmpDir());
    }
    
    public static  File file(String path, File dir) throws IOException {
        // allow for specifying ad-hoc non-resource files in tests
        File f = new File(path);
        if (f.exists()) return f;

        String filename = f.getName();
        InputStream in = ImporterTestSupport.class.getResourceAsStream("test-data/" + path);
        
        File file = new File(dir, filename);
        
        FileOutputStream out = new FileOutputStream(file);
        IOUtils.copy(in, out);
        in.close();
        out.flush();
        out.close();
    
        return file;
    }

    /**
     * Resolve a path to a stream. If the path exists, return that, otherwise
     * attempt to resolve a test-data path.
     */
    private static InputStream stream(String path) throws IOException {
        InputStream is;
        File f = new File(path);
        // support ad-hoc non-resource files in tests
        if (f.exists()) {
            is = new FileInputStream(f);
        } else {
            is = ImporterTestSupport.class.getResourceAsStream("test-data/" + path);
        }
        return is;
    }

    /**
     * Create a file KMZ with the specified contents.
     * @param parent directory to place KMZ
     * @param name name of the KMZ file (without extension)
     * @param kmlPath path to the KML to include
     * @param other other files to include
     * @return
     * @throws IOException
     */
    public static File createKMZ(File parent, String name, String kmlPath, String... other) throws IOException {
        File dest = new File(parent, name + ".kmz");
        ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(dest));
        zout.putNextEntry(new ZipEntry("doc.kml"));
        IOUtils.copy(stream(kmlPath), zout);
        for (int i = 0; i < other.length; i++) {
            String oname = FilenameUtils.getName(other[i]);
            zout.putNextEntry(new ZipEntry(oname));
            IOUtils.copy(stream(other[i]), zout);
        }
        zout.close();
        return dest;
    }

}
