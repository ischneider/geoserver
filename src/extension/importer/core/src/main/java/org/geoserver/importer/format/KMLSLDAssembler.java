/* Copyright (c) 2001 - 2013 OpenPlans - www.openplans.org. All rights reserved.
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.importer.format;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import javax.xml.transform.TransformerException;
import org.geotools.kml.StyleMap;
import org.geotools.metadata.iso.citation.OnLineResourceImpl;
import org.geotools.styling.AbstractStyleVisitor;
import org.geotools.styling.ExternalGraphic;
import org.geotools.styling.FeatureTypeStyle;
import org.geotools.styling.Graphic;
import org.geotools.styling.PointSymbolizer;
import org.geotools.styling.PolygonSymbolizer;
import org.geotools.styling.Rule;
import org.geotools.styling.SLDTransformer;
import org.geotools.styling.Style;
import org.geotools.styling.StyleBuilder;
import org.geotools.styling.StyleFactory;
import org.geotools.styling.StyledLayerDescriptor;
import org.geotools.styling.Symbolizer;
import org.geotools.styling.UserLayer;
import org.opengis.filter.Filter;
import org.opengis.filter.FilterFactory;
import org.opengis.filter.PropertyIsEqualTo;
import org.opengis.filter.expression.Expression;
import org.opengis.metadata.citation.OnLineResource;

/**
 * Assemble an SLD based on the captured elements from the KML. This cannot
 * be done inline during parsing as style elements may appear at the end
 * of the document. To work correctly, the KML parser is configured to capture
 * style URIs in the features and collect a mapping of URI to FeatureTypeStyle.
 * This class will add a filter to each Rule that makes it apply to that specific
 * URI. In addition, this class will rewrite relative URIs so local resources
 * can be served via the geoserver style endpoint.
 */
public class KMLSLDAssembler {

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
        style.accept(rewriter);
    }

    public void setSLDName(String sldName) {
        this.sld.setName(sldName);
    }

    public void write(PrintStream out, StyleMap map, String resourceUriPrefix) {
        SLDTransformer aTransformer = new SLDTransformer();
        aTransformer.setIndentation(4);

        assemble(map, resourceUriPrefix);
        try {
            aTransformer.transform(sld, new PrintWriter(out));
        } catch (TransformerException te) {
            throw new RuntimeException(te);
        }
    }

    private void assemble(StyleMap map, String resourceUriPrefix) {
        StyleFactory sf = styleBuilder.getStyleFactory();
        FilterFactory ff = styleBuilder.getFilterFactory();
        UserLayer layer = sf.createUserLayer();
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
        Graphic defaultGraphic = styleBuilder.createGraphic();
        defaultGraphic.graphicalSymbols().add(pin);
        point.setGraphic(defaultGraphic);
        style.featureTypeStyles().add(defaultStyle);

        layer.addUserStyle(style);
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
