/* Copyright (c) 2001 - 2013 OpenPlans - www.openplans.org. All rights reserved.
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.importer.format;

import java.awt.Color;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import javax.xml.transform.TransformerException;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.geoserver.importer.Importer;
import org.geotools.kml.StyleMap;
import org.geotools.metadata.iso.citation.OnLineResourceImpl;
import org.geotools.styling.AbstractStyleVisitor;
import org.geotools.styling.ExternalGraphic;
import org.geotools.styling.FeatureTypeStyle;
import org.geotools.styling.Graphic;
import org.geotools.styling.NamedLayer;
import org.geotools.styling.PointSymbolizer;
import org.geotools.styling.PolygonSymbolizer;
import org.geotools.styling.Rule;
import org.geotools.styling.SLDTransformer;
import org.geotools.styling.Style;
import org.geotools.styling.StyleBuilder;
import org.geotools.styling.StyleFactory;
import org.geotools.styling.StyledLayerDescriptor;
import org.geotools.styling.Symbolizer;
import org.geotools.util.logging.Logging;
import org.opengis.filter.Filter;
import org.opengis.filter.FilterFactory;
import org.opengis.filter.PropertyIsEqualTo;
import org.opengis.metadata.citation.OnLineResource;

/**
 * Assemble an SLD based on the captured elements from the KML. This cannot be
 * done inline during parsing as style elements may appear at the end of the
 * document. To work correctly, the KML parser is configured to capture style
 * URIs in the features and collect a mapping of URI to FeatureTypeStyle. This
 * class will add a filter to each Rule that makes it apply to that specific
 * URI. In addition, this class will rewrite relative URIs so local resources
 * can be served via the geoserver style endpoint and process any ExternalGraphic
 * objects that have been marked to color or scale.
 */
public class KMLSLDAssembler {

    static final Logger LOGGER = Logging.getLogger(Importer.class);

    private final StyleBuilder styleBuilder;
    private final StyledLayerDescriptor sld;
    private final URIRewriter rewriter;

    public KMLSLDAssembler() {
        this.styleBuilder = new StyleBuilder();
        this.sld = styleBuilder.getStyleFactory().createStyledLayerDescriptor();
        this.rewriter = new URIRewriter();
    }

    private void rewriteRelativeURIs(FeatureTypeStyle style, String resourceUriPrefix) {
        rewriter.resourceUriPrefix = resourceUriPrefix;
        if (resourceUriPrefix != null) {
            style.accept(rewriter);
        }
    }

    public void setSLDName(String sldName) {
        this.sld.setName(sldName);
    }

    /**
     * Process and write out an SLD generated from the StyleMap. Any relative
     * URI references for OnLineResources will be prefixed with the specified
     * resourceUriPrefix. Any colored/scaled ExternalGraphics will be resolved
     * (if relative) from the relativeRoot and written to the destination.
     */
    public void write(PrintStream out, StyleMap map, String resourceUriPrefix,
            File relativeRoot, File destination) {
        List<ExternalGraphic> graphics = getExternalGraphics(map);
        resolveMissingMimeType(graphics);
        processColoredIcons(graphics, relativeRoot, destination, resourceUriPrefix);

        SLDTransformer aTransformer = new SLDTransformer();
        aTransformer.setIndentation(4);
        assemble(map, resourceUriPrefix);
        try {
            aTransformer.transform(sld, new PrintWriter(out));
        } catch (TransformerException te) {
            throw new RuntimeException(te);
        }
    }

    private boolean isMimeTypeMissing(ExternalGraphic eg) {
        return eg.getFormat() == null || eg.getFormat().equals("unknown");
    }

    /**
     * Some URI might not contain a file extension. Sniff these out, if possible.
     */
    private void resolveMissingMimeType(List<ExternalGraphic> graphics) {
        Object[][] lookup = new Object[][] {
            {"image/png", new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47}},
            {"image/jpeg", new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0}},
            {"image/gif", new byte[]{0x47, 0x49, 0x46, 0x38}}
        };
        // make sure we don't revisit
        Map<URI,String> resolved = new HashMap<URI, String>();
        for (ExternalGraphic eg: graphics) {
            if (eg.getOnlineResource() != null) {
                boolean missingFormat = isMimeTypeMissing(eg);
                if (missingFormat && eg.getOnlineResource() != null) {
                    URI uri = eg.getOnlineResource().getLinkage();
                    if (uri != null) {
                        String mimeType = resolved.get(uri);
                        if (mimeType != null) {
                            eg.setFormat(mimeType);
                            continue;
                        }
                        InputStream in;
                        byte[] magic = new byte[8];
                        try {
                            in = uri.toURL().openStream();
                            if (in.read(magic) == 4) {
                                for (int i = 0; i < lookup.length; i++) {
                                    if (Arrays.equals(magic, (byte[])lookup[i][1])) {
                                        mimeType = (String) lookup[i][0];
                                        eg.setFormat(mimeType);
                                        resolved.put(uri, mimeType);
                                        break;
                                    }
                                }
                            }
                        } catch (Exception ex) {
                            LOGGER.log(Level.WARNING, "Error resolving mimetype of " + uri,
                                LOGGER.isLoggable(Level.FINE) ? ex : null
                            );
                        }
                    }
                }
            }
        }
    }

    /**
     * Find any ExternalGraphics that have custom properties of either scale or color,
     * resolve the image and color/scale if specified writing the image out with
     * a name prepended with the color and width of the resulting image. Any
     * processed ExternalGraphic will have their URI updated to reflect that of
     * the generated image.
     */
    private void processColoredIcons(List<ExternalGraphic> graphics, File relativeRoot, File destination,
            String resourceUriPrefix) {
        // keep track of generated icons List of scale, color, URI
        Map<List, File> processed = new HashMap<List, File>();
        Map<URL, File> fetched = new HashMap<URL, File>();

        for (ExternalGraphic eg : getGraphicsToProcess(graphics)) {

            Number scale = (Number) eg.getCustomProperties().get("scale");
            Color color = (Color) eg.getCustomProperties().get("color");

            URI linkage = eg.getOnlineResource().getLinkage();

            // a key to unqiuely identified the generated image
            List processKey = Arrays.asList(scale, color, linkage);

            File tinted = processed.get(processKey);
            if (tinted != null) {
                update(eg, resourceUriPrefix, tinted);
                continue;
            }

            // if no tinted file but a key is recorded, this means a failure in
            // resolving the URI, so skip it
            if (processed.containsKey(processKey)) {
                continue;
            }

            URL url = resolveURL(relativeRoot, linkage);
            if (url == null) {
                continue;
            }

            downloadIfNeeded(url, destination, fetched);

            tinted = processImage(url, color, scale, destination);

            if (tinted != null) {
                update(eg, resourceUriPrefix, tinted);
            }

            // put a key here regardless of resolving to ensure we don't try again
            processed.put(processKey, tinted);
        }

        // we don't need these anymore
        for (File f : fetched.values()) {
            // watch out for our marker
            if (f != null) {
                f.delete();
            }
        }
    }

    private File processImage(URL url, Color color, Number scale, File dir) {
        BufferedImage bi;
        try {
            bi = ImageIO.read(url);
        } catch (IOException ex) {
            LOGGER.warning("Error reading image from " + url);
            return null;
        }

        String name = FilenameUtils.getName(url.getPath());
        if (color != null) {
            colorize(bi, color);
            name = Integer.toHexString(color.getRGB()) + "-" + name;
        }
        if (scale != null) {
            AffineTransform tx = new AffineTransform();
            tx.scale(scale.doubleValue(), scale.doubleValue());
            bi = new AffineTransformOp(tx, AffineTransformOp.TYPE_BILINEAR).filter(bi, null);
            name = bi.getWidth() + "-" + name;
        }

        File tinted = null;
        try {
            tinted = new File(dir, name);
            ImageIO.write(bi, FilenameUtils.getExtension(name), tinted);
        } catch (IOException ex) {
            LOGGER.warning("error writing image " + ex.getMessage());
        }
        return tinted;
    }
    
    private URL resolveURL(File relativeRoot, URI uri) {
        if (!uri.isAbsolute()) {
            uri = new File(relativeRoot, uri.getPath()).toURI();
        }
        URL url = null;
        try {
            url = uri.toURL();
        } catch (MalformedURLException ex) {
            LOGGER.warning("invalid URL " + uri);
        }
        return url;
    }

    private URL downloadIfNeeded(URL url, File destination, Map<URL, File> fetched) {
        // if we have to, download a remote image
        if (!"file".equals(url.getProtocol()) && !fetched.containsKey(url)) {
            LOGGER.fine("fetching " + url);
            InputStream stream = null;
            FileOutputStream fw = null;
            try {
                stream = url.openStream();
                String fileName = FilenameUtils.getName(url.getPath());
                File tmp = new File(destination, System.identityHashCode(stream) + fileName);
                IOUtils.copy(stream, fw = new FileOutputStream(tmp));
                fetched.put(url, tmp);
                url = tmp.toURI().toURL();
            } catch (IOException ex) {
                LOGGER.warning("error fetching " + url + " : " + ex.getMessage());
                // let's not keep trying to fetch a missing/broken one
                fetched.put(url, null);
            } finally {
                IOUtils.closeQuietly(stream);
                IOUtils.closeQuietly(fw);
            }
        }
        return url;
    }

    private List<ExternalGraphic> getExternalGraphics(StyleMap map) {
        List<ExternalGraphic> eg = new ArrayList<ExternalGraphic>();
        for (URI uri : map.keys()) {
            FeatureTypeStyle fts = map.get(uri);

            // if there are no symbolizers, skip. this happens from StyleMap elements
            if (fts.rules().get(0).symbolizers().isEmpty()) {
                continue;
            }
            Symbolizer symbolizer = fts.rules().get(0).symbolizers().get(0);
            if (symbolizer instanceof PointSymbolizer) {

                Graphic g = ((PointSymbolizer) symbolizer).getGraphic();
                eg.add((ExternalGraphic) g.graphicalSymbols().get(0));
            }
        }
        return eg;
    }

    private List<ExternalGraphic> getGraphicsToProcess(List<ExternalGraphic> graphics) {
        List<ExternalGraphic> toProcess = new ArrayList<ExternalGraphic>();
        for (ExternalGraphic eg : graphics) {
            // we tried to resolve already, if still missing, the link is probably broken
            if (isMimeTypeMissing(eg)) {
                continue;
            }
            if (eg.getCustomProperties() == null) {
                continue;
            }
            Number scale = (Number) eg.getCustomProperties().get("scale");
            Color color = (Color) eg.getCustomProperties().get("color");
            if (scale == null && color == null) {
                continue;
            }
            toProcess.add(eg);
        }
        return toProcess;
    }

    private void update(ExternalGraphic eg, String prefix, File file) {
        String update = prefix + "/" + file.getName();
        if (LOGGER.isLoggable(Level.FINE)) {
            OnLineResource r = eg.getOnlineResource();
            LOGGER.fine("update " + r.getLinkage() + " to " + update);
        }
        eg.setURI(update);
    }

    static void colorize(BufferedImage bi, Color tint) {
        // get/setRGB work fine with indexed (gif) or images w/o alpha channel (jpg)
        // does a 0 alpha tint even make sense?
        final float a1 = tint.getAlpha() == 0 ? 0 : tint.getAlpha() / 255f;
        final int r1 = tint.getRed();
        final int g1 = tint.getGreen();
        final int b1 = tint.getBlue();
        for (int x = 0; x < bi.getWidth(); x++) {
            for (int y = 0; y < bi.getHeight(); y++) {
                int rgba = bi.getRGB(x, y);
                int a = (rgba >> 24) & 0xFF;
                int r = (rgba >> 16) & 0xFF;
                int g = (rgba >> 8) & 0xFF;
                int b = rgba & 0xFF;
                int Y = (int) (0.3 * r + 0.59 * g + 0.11 * b);
                r = (r1 + Y) / 2;
                g = (g1 + Y) / 2;
                b = (b1 + Y) / 2;
                a *= a1; // scale alpha - seems to work okay?
                int value = ((a & 0xFF) << 24)
                        | ((r & 0xFF) << 16)
                        | ((g & 0xFF) << 8)
                        | ((b & 0xFF));
                bi.setRGB(x, y, value);
            }
        }
    }

    private void assemble(StyleMap map, String resourceUriPrefix) {
        StyleFactory sf = styleBuilder.getStyleFactory();
        FilterFactory ff = styleBuilder.getFilterFactory();
        NamedLayer layer = sf.createNamedLayer();
        layer.setName(sld.getName());
        sld.addStyledLayer(layer);
        Style style = styleBuilder.createStyle();
        List<URI> keys = new ArrayList<URI>(map.keys());
        Collections.sort(keys);
        for (URI uri : keys) {
            FeatureTypeStyle fts = map.get(uri);
            // if there are no symbolizers, skip. this happens from StyleMap elements
            if (fts.rules().get(0).symbolizers().isEmpty()) {
                continue;
            }

            // rebuild using the uri to avoid modifying the original
            // the uri may be aliased to another style
            FeatureTypeStyle duplicate = sf.createFeatureTypeStyle();
            duplicate.setName(uri.toString());

            // filter to select features by the value of their Style attribute
            PropertyIsEqualTo styleFilter = ff.equal(ff.property("Style"), ff.literal(uri.toString()), false);

            // if there is a polygon symbolizer, modify to apply only if
            // the geometry is a polygon (like google earth does)
            List<Symbolizer> symbolizers = fts.rules().get(0).symbolizers();
            for (Iterator i = symbolizers.iterator(); i.hasNext();) {
                Object next = i.next();
                if (next instanceof PolygonSymbolizer) {
                    // remove it so the other rule doesn't get this
                    i.remove();

                    Rule rule = styleBuilder.createRule((PolygonSymbolizer) next, 0, Double.POSITIVE_INFINITY);
                    Filter polyFilter = ff.equal(ff.function("geometryType", ff.property("Geometry")), ff.literal("Polygon"), false);
                    rule.setFilter(ff.and(styleFilter, polyFilter));
                    duplicate.rules().add(rule);
                }
            }

            Rule rule = styleBuilder.createRule(symbolizers.toArray(new Symbolizer[0]), 0, Double.POSITIVE_INFINITY);
            rule.setFilter(styleFilter);
            duplicate.rules().add(rule);

            rewriteRelativeURIs(duplicate, resourceUriPrefix);

            style.featureTypeStyles().add(duplicate);
        }

        // build a default pushpin style to correspond to any missing styles
        FeatureTypeStyle defaultStyle = sf.createFeatureTypeStyle();
        defaultStyle.setName("defaultPushpinStyle");
        PointSymbolizer point = sf.createPointSymbolizer();
        Rule rule = sf.createRule();
        rule.symbolizers().add(point);
        rule.setFilter(ff.isNull(ff.property("Style")));
        defaultStyle.rules().add(rule);
        ExternalGraphic pin = styleBuilder.createExternalGraphic(
                "http://maps.google.com/mapfiles/kml/pushpin/ylw-pushpin.png",
                "image/png");
        Graphic defaultGraphic = styleBuilder.createGraphic(pin, null, null);
        point.setGraphic(defaultGraphic);
        style.featureTypeStyles().add(defaultStyle);

        layer.addStyle(style);
    }

    static class URIRewriter extends AbstractStyleVisitor {

        String resourceUriPrefix;

        @Override
        public void visit(ExternalGraphic exgr) {
            try {
                OnLineResource onlineResource = exgr.getOnlineResource();
                URI linkage = onlineResource.getLinkage();
                if (!linkage.isAbsolute()) {
                    linkage = new URI(resourceUriPrefix + "/" + onlineResource.getLinkage());
                    ((OnLineResourceImpl) onlineResource).setLinkage(linkage);
                }
            } catch (URISyntaxException ex) {
                throw new RuntimeException(ex);
            }
        }
    }
}
