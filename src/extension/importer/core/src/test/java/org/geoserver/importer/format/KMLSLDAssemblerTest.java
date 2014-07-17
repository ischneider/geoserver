/* Copyright (c) 2001 - 2013 OpenPlans - www.openplans.org. All rights reserved.
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.importer.format;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import javax.imageio.ImageIO;
import org.geoserver.importer.ImporterTestUtils;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class KMLSLDAssemblerTest {

    @Test
    public void testKMLAssemblerColorize() throws Exception {
        File tmp = ImporterTestUtils.tmpDir();

        KMLRawReader reader = KMLRawReader.buildFeatureParser(getClass().getResourceAsStream("/org/geotools/kml/v22/styles.kml"), null);
        while (reader.parse() != null);

        makeImage(tmp, "2.png", Color.WHITE);
        makeImage(tmp, "4.png", Color.YELLOW);

        KMLSLDAssembler assembler = new KMLSLDAssembler();
        assembler.setSLDName("test");
        assembler.write(new PrintStream(new ByteArrayOutputStream()), reader.getStyleMap(), null, tmp, tmp);

        // verify the prefixed, colored images exist
        assertTrue(new File(tmp, "52-d0551e0d-ylw-pushpin.png").exists());
        assertTrue(new File(tmp, "7-ff0000ff-4.png").exists());
    }

    void makeImage(File dir, String name, Color color) throws IOException {
        BufferedImage bi = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);

        Graphics2D g = bi.createGraphics();
        g.setColor(color);
        g.fillOval(0, 0, 16, 16);
        g.dispose();

        ImageIO.write(bi, name.split("\\.")[1], new File(dir, name));
    }
}
