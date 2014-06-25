/* Copyright (c) 2001 - 2013 OpenPlans - www.openplans.org. All rights reserved.
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.wfs.json;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.opengis.wfs.QueryType;
import net.opengis.wfs.GetFeatureType;
import net.sf.json.JSONException;

import org.eclipse.emf.common.util.EList;
import org.geoserver.catalog.Catalog;
import org.geoserver.catalog.FeatureTypeInfo;
import org.geoserver.config.GeoServer;
import org.geoserver.ows.Dispatcher;
import org.geoserver.ows.Request;
import org.geoserver.platform.Operation;
import org.geoserver.platform.ServiceException;
import org.geoserver.wfs.WFSGetFeatureOutputFormat;
import org.geoserver.wfs.WFSInfo;
import org.geoserver.wfs.request.FeatureCollectionResponse;
import org.geoserver.wfs.request.GetFeatureRequest;
import org.geotools.data.FeatureSource;
import org.geotools.data.Query;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.NamedIdentifier;
import org.opengis.feature.Feature;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.AttributeDescriptor;
import org.opengis.feature.type.FeatureType;
import org.opengis.feature.type.GeometryDescriptor;
import org.opengis.filter.Filter;
import org.opengis.referencing.ReferenceIdentifier;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import javax.xml.namespace.QName;

import com.vividsolutions.jts.geom.Geometry;

/**
 * A GetFeatureInfo response handler specialized in producing Json and JsonP data for a GetFeatureInfo request.
 * 
 * @author Simone Giannecchini, GeoSolutions
 * @author Carlo Cancellieri - GeoSolutions
 * 
 */
public class GeoJSONGetFeatureResponse extends WFSGetFeatureOutputFormat {
    private final Logger LOGGER = org.geotools.util.logging.Logging.getLogger(this.getClass());

    // store the response type
    private final boolean jsonp;

    public GeoJSONGetFeatureResponse(GeoServer gs, String format) {
        super(gs, format);
        if (JSONType.isJsonMimeType(format)) {
            jsonp = false;
        } else if (JSONType.isJsonpMimeType(format)) {
            jsonp = true;
        } else {
            throw new IllegalArgumentException(
                    "Unable to create the JSON Response handler using format: " + format
                            + " supported mymetype are: "
                            + Arrays.toString(JSONType.getSupportedTypes()));
        }
    }

    /**
     * capabilities output format string.
     */
    public String getCapabilitiesElementName() {
        return JSONType.getJSONType(getOutputFormat()).toString();
    }

    /**
     * Returns the mime type
     */
    public String getMimeType(Object value, Operation operation) throws ServiceException {
        if(jsonp) {
            return JSONType.JSONP.getMimeType();
        } else {
            return JSONType.JSON.getMimeType();
        }
    }

    @Override
    protected void write(FeatureCollectionResponse featureCollection, OutputStream output,
            Operation describeFeatureType) throws IOException {

        if (LOGGER.isLoggable(Level.INFO))
            LOGGER.info("about to encode JSON");
        // Generate bounds for every feature?
        WFSInfo wfs = getInfo();
        boolean featureBounding = wfs.isFeatureBounding();
        
        // include fid?
        String id_option = null; // null - default, "" - none, or "property"
        //GetFeatureRequest request = GetFeatureRequest.adapt(describeFeatureType.getParameters()[0]);
        Request request = Dispatcher.REQUEST.get();
        if (request != null) {
            id_option = JSONType.getIdPolicy( (Map<String,String>) request.getKvp() );
        }
        // prepare to write out
        OutputStreamWriter osw = null;
        Writer outWriter = null;

        // get feature count for request
        Integer featureCount = null; 
        // for WFS 1.0.0 and WFS 1.1.0 a request with the query must be executed
        if(describeFeatureType != null) {
            if (describeFeatureType.getParameters()[0] instanceof GetFeatureType) {
                featureCount = getFeatureCountFromWFS11Request(describeFeatureType, wfs);
            }
            // for WFS 2.0.0 the total number of features is stored in the featureCollection
            else if (describeFeatureType.getParameters()[0] instanceof net.opengis.wfs20.GetFeatureType){
                featureCount = featureCollection.getTotalNumberOfFeatures().intValue(); 
            }
        }
        
        try {
            osw = new OutputStreamWriter(output, gs.getSettings().getCharset());
            outWriter = new BufferedWriter(osw);

            if (jsonp) {
                outWriter.write(getCallbackFunction() + "(");
            }

            final GeoJSONBuilder jsonWriter = new GeoJSONBuilder(outWriter);
            jsonWriter.object().key("type").value("FeatureCollection");
            if(featureCount != null) {
                jsonWriter.key("totalFeatures").value(featureCount);
            }
            jsonWriter.key("features");
            jsonWriter.array();

            // execute should of set all the header information
            // including the lockID
            //
            // execute should also fail if all of the locks could not be aquired
            List<FeatureCollection> resultsList = featureCollection.getFeature();
            for (int i = 0; i < resultsList.size(); i++) {
                jsonWriter.featureCollection(resultsList.get(i));
            }
            jsonWriter.endArray(); // end features
            CoordinateReferenceSystem crs = jsonWriter.getCrs();
            boolean hasGeom = jsonWriter.isHasGeometry();

            // Coordinate Referense System, currently only if the namespace is
            // EPSG
            if (crs != null) {
                Set<ReferenceIdentifier> ids = crs.getIdentifiers();
                // WKT defined crs might not have identifiers at all
                if (ids != null && ids.size() > 0) {
                    NamedIdentifier namedIdent = (NamedIdentifier) ids.iterator().next();
                    String csStr = namedIdent.getCodeSpace().toUpperCase();

                    if (csStr.equals("EPSG")) {
                        jsonWriter.key("crs");
                        jsonWriter.object();
                        jsonWriter.key("type").value(csStr);
                        jsonWriter.key("properties");
                        jsonWriter.object();
                        jsonWriter.key("code");
                        jsonWriter.value(namedIdent.getCode());
                        jsonWriter.endObject(); // end properties
                        jsonWriter.endObject(); // end crs
                    }
                }
            }

            // Bounding box for featurecollection
            if (hasGeom && featureBounding) {
                ReferencedEnvelope e = null;
                for (int i = 0; i < resultsList.size(); i++) {
                    FeatureCollection collection = resultsList.get(i);
                    if (e == null) {
                        e = collection.getBounds();
                    } else {
                        e.expandToInclude(collection.getBounds());
                    }

                }

                if (e != null) {
                    jsonWriter.writeBoundingBox(e);
                }
            }

            jsonWriter.endObject(); // end featurecollection

            if (jsonp) {
                outWriter.write(")");
            }

            outWriter.flush();

        } catch (JSONException jsonException) {
            ServiceException serviceException = new ServiceException("Error: "
                    + jsonException.getMessage());
            serviceException.initCause(jsonException);
            throw serviceException;
        }
    }

    private String getCallbackFunction() {
        Request request = Dispatcher.REQUEST.get();
        if (request == null) {
            return JSONType.CALLBACK_FUNCTION;
        }
        return JSONType.getCallbackFunction(request.getKvp());
    }

    
    /**
     * getFeatureCountFromWFS11Request
     * 
     * Function gets the total number of features from a WFS 1.0.0 or WFS 1.1.0 request and returns it.
     * 
     * @param describeFeatureType
     * @param wfs
     * @return int featurecount 
     * @throws IOException
     */
    @SuppressWarnings("unchecked")
    private int getFeatureCountFromWFS11Request(Operation describeFeatureType, WFSInfo wfs)
            throws IOException {
        int totalCount = 0;
        Catalog catalog = wfs.getGeoServer().getCatalog();
        
        GetFeatureType request = (GetFeatureType) describeFeatureType.getParameters()[0];
        
        for (QueryType query :  (EList<QueryType>) request.getQuery()) {
            QName typeName = (QName) query.getTypeName().get(0);
            FeatureTypeInfo meta = catalog.getFeatureTypeByName(typeName.getNamespaceURI(),
                    typeName.getLocalPart());

            FeatureSource<? extends FeatureType, ? extends Feature> source = meta.getFeatureSource(
                    null, null);
            Filter filter = query.getFilter();
            if (filter == null) {
                filter = Filter.INCLUDE;
            }
            Query countQuery = new Query(typeName.getLocalPart(), filter);
            
            int count = 0;
            count = source.getCount(countQuery);
            if (count == -1) {
                // information was not available in the header!
                org.geotools.data.Query gtQuery = new org.geotools.data.Query(countQuery);
                FeatureCollection<? extends FeatureType, ? extends Feature> features = source
                        .getFeatures(gtQuery);
                count = features.size();
            }
            totalCount +=count;
        }

        return totalCount;
    }
}
