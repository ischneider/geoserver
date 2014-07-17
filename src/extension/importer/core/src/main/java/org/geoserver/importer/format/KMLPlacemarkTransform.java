/* Copyright (c) 2001 - 2013 OpenPlans - www.openplans.org. All rights reserved.
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.importer.format;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.apache.commons.lang.StringUtils;
import org.geoserver.importer.FeatureDataConverter;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.kml.Folder;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;

public class KMLPlacemarkTransform {
    private final SimpleFeatureBuilder fb;
    private final SimpleFeatureType targetFeatureType;
    private String rewritePrefix;
    private List<String> rewritePaths;

    public KMLPlacemarkTransform(SimpleFeatureType targetFeatureType) {
        this.targetFeatureType = targetFeatureType;
        fb = new SimpleFeatureBuilder(targetFeatureType);
    }

    public SimpleFeature convertFeature(SimpleFeature old) {
        SimpleFeature newFeature = fb.buildFeature(old.getID());
        FeatureDataConverter.DEFAULT.convert(old, newFeature);
        Map<Object, Object> userData = old.getUserData();
        Object folderObject = userData.get("Folder");
        if (folderObject != null) {
            String serializedFolders = serializeFolders(folderObject);
            newFeature.setAttribute("Folder", serializedFolders);
        }
        URI styleURI = (URI) old.getAttribute("Style");
        if (styleURI != null) {
            newFeature.setAttribute("Style", styleURI.toString());
        }
        if (rewritePrefix != null) {
            String description = (String) old.getAttribute("description");
            if (description != null) {
                for (int i = 0; i < rewritePaths.size(); i++) {
                    String path = rewritePaths.get(i);
                    description = description.replace(path, rewritePrefix + path);
                }
            }
            newFeature.setAttribute("description", description);
        }
        @SuppressWarnings("unchecked")
        Map<String, String> untypedExtendedData = (Map<String, String>) userData
                .get("UntypedExtendedData");
        if (untypedExtendedData != null) {
            for (Entry<String, String> entry : untypedExtendedData.entrySet()) {
                if (targetFeatureType.getDescriptor(entry.getKey()) != null) {
                    newFeature.setAttribute(entry.getKey(), entry.getValue());
                }
            }
        }
        return newFeature;
    }

    private static String serializeFolders(Object folderObject) {
        @SuppressWarnings("unchecked")
        List<Folder> folders = (List<Folder>) folderObject;
        List<String> folderNames = new ArrayList<String>(folders.size());
        for (Folder folder : folders) {
            String name = folder.getName();
            if (!StringUtils.isEmpty(name)) {
                folderNames.add(name);
            }
        }
        String serializedFolders = StringUtils.join(folderNames.toArray(), " -> ");
        return serializedFolders;
    }

    void setRewritePaths(String prefix, List<String> paths) {
        this.rewritePrefix = prefix;
        this.rewritePaths = paths;
    }

}
