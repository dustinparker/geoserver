/*
 * Copyright (c) 2001 - 2013 OpenPlans - www.openplans.org. All rights reserved.
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.wfs.json;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

import net.sf.json.JSONException;

import net.sf.json.util.JSONUtils;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.feature.NameImpl;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.geometry.jts.coordinatesequence.CoordinateSequences;
import org.geotools.util.Converters;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.CoordinateSequence;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryCollection;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.geom.MultiLineString;
import com.vividsolutions.jts.geom.MultiPoint;
import com.vividsolutions.jts.geom.MultiPolygon;
import com.vividsolutions.jts.geom.Point;
import com.vividsolutions.jts.geom.Polygon;
import com.vividsolutions.jts.geom.impl.CoordinateArraySequence;
import org.opengis.feature.ComplexAttribute;
import org.opengis.feature.Feature;
import org.opengis.feature.GeometryAttribute;
import org.opengis.feature.Property;
import org.opengis.feature.type.FeatureType;
import org.opengis.feature.type.GeometryDescriptor;
import org.opengis.feature.type.Name;
import org.opengis.feature.type.PropertyDescriptor;
import org.opengis.referencing.crs.CoordinateReferenceSystem;


/**
 * This class extends the JSONBuilder to be able to write out geometric types.  It is coded
 * against the draft 5 version of the spec on http://geojson.org
 *
 * @author Chris Holmes, The Open Planning Project
 * @version $Id$
 *
 */
public class GeoJSONBuilder extends UnboundedJSONBuilder {
    private static final Logger LOGGER = org.geotools.util.logging.Logging.getLogger(GeoJSONBuilder.class);
    private static final Name FEATURE_LINK = new NameImpl("FEATURE_LINK");

    private final boolean featureBounding;
    private boolean hasGeometry = false;
    private CoordinateReferenceSystem crs;

    public GeoJSONBuilder(Writer w) {
        this(w, false);
    }

    public GeoJSONBuilder(Writer w, boolean featureBounding) {
        super(w);
        this.featureBounding = featureBounding;
    }

    public boolean isHasGeometry() {
        return hasGeometry;
    }

    public CoordinateReferenceSystem getCrs() {
        return crs;
    }

    /**
     * Writes any geometry object.  This class figures out which geometry representation to write
     * and calls subclasses to actually write the object.
     * @param geometry The geoemtry be encoded
     * @return The JSONBuilder with the new geoemtry
     * @throws JSONException If anything goes wrong
     */
    public UnboundedJSONBuilder writeGeom(Geometry geometry) throws JSONException {
        object();
        key("type");

        final GeometryType geometryType = getGeometryType(geometry.getClass());
        value(geometryType.name);

        if (geometryType != GeometryType.MULTIGEOMETRY) {
            key("coordinates");

            switch (geometryType) {
            case POINT:
                Point point = (Point) geometry;
                Coordinate c = point.getCoordinate();
                writeCoordinate(c.x, c.y, c.z);
                break;
            case LINESTRING:
                writeCoordinates(((LineString)geometry).getCoordinateSequence());
                break;
            case MULTIPOINT:
                writeCoordinates(geometry.getCoordinates());
                break;
            case POLYGON:
                writePolygon((Polygon) geometry);

                break;

            case MULTILINESTRING:
                array();

                for (int i = 0, n = geometry.getNumGeometries(); i < n; i++) {
                    writeCoordinates(((LineString)geometry.getGeometryN(i)).getCoordinateSequence());
                }

                endArray();

                break;

            case MULTIPOLYGON:
                array();

                for (int i = 0, n = geometry.getNumGeometries(); i < n; i++) {
                    writePolygon((Polygon) geometry.getGeometryN(i));
                }

                endArray();

                break;
            }
        } else {
            writeGeomCollection((GeometryCollection) geometry);
        }

        return endObject();
    }

    private UnboundedJSONBuilder writeGeomCollection(GeometryCollection collection) {
        key("geometries");
        array();

        for (int i = 0, n = collection.getNumGeometries(); i < n; i++) {
            writeGeom(collection.getGeometryN(i));
        }

        return endArray();
    }

    private UnboundedJSONBuilder writeCoordinates(Coordinate[] coords)
        throws JSONException {
        return writeCoordinates(new CoordinateArraySequence(coords));
    }
    
    /**
     * Write the coordinates of a geometry
     * @param coords The coordinates to write
     * @return this
     * @throws JSONException
     */
    private UnboundedJSONBuilder writeCoordinates(CoordinateSequence coords)
        throws JSONException {
        array();
        
        // guess the dimension of the coordinate sequence
        int dim = CoordinateSequences.coordinateDimension(coords);

        final int coordCount = coords.size();
        for (int i = 0; i < coordCount; i++) {
            if(dim > 2) {
                writeCoordinate(coords.getX(i), coords.getY(i), coords.getOrdinate(i, 2));
            } else {
                writeCoordinate(coords.getX(i), coords.getY(i));
            }
        }

        return endArray();
    }

    private UnboundedJSONBuilder writeCoordinate(double x, double y) {
        array();
        value(x);
        value(y);

        return endArray();
    }
    
    private UnboundedJSONBuilder writeCoordinate(double x, double y, double z) {
        array();
        value(x);
        value(y);
        if(!Double.isNaN(z)) {
            value(z);
        }

        return endArray();
    }

    
    
    /**
     * Turns an envelope into an array [minX,minY,maxX,maxY]
     * @param env envelope representing bounding box
     * @return this
     */
    protected UnboundedJSONBuilder writeBoundingBox(Envelope env) {
        key("bbox");
        array();
        value(env.getMinX());
        value(env.getMinY());
        value(env.getMaxX());
        value(env.getMaxY());
        return endArray();
    }

    /**
     * Writes a polygon
     * @param geometry The polygon to write
     * @throws JSONException
     */
    private void writePolygon(Polygon geometry) throws JSONException {
        array();
        writeCoordinates(geometry.getExteriorRing().getCoordinateSequence());

        for (int i = 0, ii = geometry.getNumInteriorRing(); i < ii; i++) {
            writeCoordinates(geometry.getInteriorRingN(i).getCoordinateSequence());
        }

        endArray();
    }
    
    protected static enum GeometryType {
        POINT(Point.class, "Point"),
        LINESTRING(LineString.class, "LineString"),
        POLYGON(Polygon.class, "Polygon"),
        MULTIPOINT(MultiPoint.class, "MultiPoint"),
        MULTILINESTRING(MultiLineString.class, "MultiLineString"),
        MULTIPOLYGON(MultiPolygon.class, "MultiPolygon"),
        MULTIGEOMETRY(GeometryCollection.class, "MultiGeometry");
        
        final Class<? extends Geometry> clazz;
        final String name;
        
        private GeometryType(Class<? extends Geometry> clazz, String name) {
            this.clazz = clazz;
            this.name = name;
        }
    }

    protected static final Map<Class<? extends Geometry>, GeometryType> GEOMETRY_CLASS_BY_NAME;
    protected static final String[] GEOMETRY_NAME =
        { null, "Point", "LineString", "Polygon", "MultiPoint",
                "MultiLineString", "MultiPolygon", "MultiGeometry" };
    
    static {
        Map<Class<? extends Geometry>, GeometryType> m = GEOMETRY_CLASS_BY_NAME = new IdentityHashMap<Class<? extends Geometry>, GeometryType>(GeometryType.values().length);
        for(GeometryType gc : GeometryType.values()) {
            m.put(gc.clazz, gc);
        }
    }
    
    /**
     * Gets the internal representation for the given Geometry
     *
     * @param geomClass a class of Geometry
     *
     * @return metadata about the Geometry class
     */
    public static GeometryType getGeometryType(Class<? extends Geometry> geomClass) {
        return GEOMETRY_CLASS_BY_NAME.get(geomClass);
    }

    /**
     * Overrides to handle the case of encoding {@code java.util.Date} and its date/time/timestamp
     * descendants, as well as {@code java.util.Calendar} instances as ISO 8601 strings.
     * 
     * @see net.sf.json.util.JSONBuilder#value(java.lang.Object)
     */
    @Override
    public GeoJSONBuilder value(Object value) {
        if (value instanceof java.util.Date || value instanceof Calendar) {
            value = Converters.convert(value, String.class);
        }
        super.value(value);
        return this;
    }

    public <T extends FeatureType, F extends Feature> GeoJSONBuilder featureCollection(FeatureCollection<T,F> collection) {
        FeatureIterator iterator = collection.features();

        try {
            while (iterator.hasNext()) {
                feature(iterator.next());
            }
        } // catch an exception here?
        finally {
            iterator.close();
        }

        return this;
    }

    public GeoJSONBuilder feature(Feature feature) {
        object();
        key("type").value("Feature");
        key("id").value(feature.getIdentifier().getID());

        key("geometry");
        GeometryDescriptor defaultGeomType = defaultGeometry(feature);

        if (defaultGeomType != null)
            key("geometry_name").value(defaultGeomType.getLocalName());

        if(crs == null && defaultGeomType != null) {
            crs = defaultGeomType.getCoordinateReferenceSystem();
        }

        key("properties");
        properties(feature, defaultGeomType);

        // Bounding box for feature in properties
        if (featureBounding) {
            ReferencedEnvelope refenv = new ReferencedEnvelope(feature.getBounds());
            if(!refenv.isEmpty())
                writeBoundingBox(refenv);
        }

        endObject(); // end the feature

        return this;
    }

    public GeoJSONBuilder properties(ComplexAttribute attr) {
        return properties(attr, null);
    }

    private GeoJSONBuilder properties(ComplexAttribute attr, PropertyDescriptor except) {
        object();

        Iterator<Property> it = attr.getProperties().iterator();

        if(!it.hasNext()) {
            endObject();
            return this;
        }

        Property prop;
        PropertyDescriptor pd;

        prop = it.next();

        do {
            pd = prop.getDescriptor();
            // This is an area of the spec where they
            // decided to 'let convention evolve',
            // that is how to handle multiple
            // geometries. My take is to print the
            // geometry here if it's not the default.
            // If it's the default that you already
            // printed above, so you don't need it here.
            if (pd.equals(except)) {
                // Do nothing, we wrote it above
                prop = it.hasNext() ? it.next() : null;
                continue;
            }

            // This is a dirty hack to keep app-schema-specific info out of
            // the result
            if (FEATURE_LINK.equals(prop.getName())) {
                prop = it.hasNext() ? it.next() : null;
                continue;
            }

            if(pd.getMaxOccurs() == 1) {
                if(!(prop == null || prop.getValue() == null)) {
                    key(pd.getName().getLocalPart());
                    property(prop);
                }

                prop = it.hasNext() ? it.next() : null;
            } else {
                key(pd.getName().getLocalPart());
                array();

                do {
                    property(prop);
                    prop = it.hasNext() ? it.next() : null;
                } while(prop != null && prop.getDescriptor().equals(pd));

                endArray();
            }

        } while(prop != null);

        endObject();

        return this;
    }

    private GeometryDescriptor defaultGeometry(Feature feature) {
        GeometryAttribute geometryProperty = feature.getDefaultGeometryProperty();
        GeometryDescriptor defaultGeomType =
            geometryProperty == null ? null : geometryProperty.getDescriptor();
        Geometry aGeom =
            (Geometry) (geometryProperty == null ? null : geometryProperty
                .getValue());

        if (aGeom == null) {
            // In case the default geometry is not set, we will
            // just use the first geometry we find
            for (Property p : feature.getProperties()) {
                Object value = p.getValue();
                if (value != null && value instanceof Geometry) {
                    aGeom = (Geometry) value;
                }
            }
        }
        // Write the geometry, whether it is a null or not
        if (aGeom != null) {
            writeGeom(aGeom);
            hasGeometry = true;
        } else {
            value(null);
        }

        return defaultGeomType;
    }

    public GeoJSONBuilder property(Property prop) {
        if(prop instanceof Feature) {
            return feature((Feature)prop);
        } else if(prop instanceof ComplexAttribute) {
            return attribute((ComplexAttribute)prop);
        }


        if (prop != null) {
            Object value = prop.getValue();
            if (value instanceof Geometry) {
                writeGeom((Geometry) value);
            } else {
                value(value);
            }
        } else {
            value(null);
        }

        return this;
    }

    public GeoJSONBuilder attribute(ComplexAttribute attr) {
        properties(attr);
        return this;
    }
}

class UnboundedJSONBuilder {
    protected char mode = 'i';
    protected char[] stack = new char[20];
    protected int top = 0;
    protected boolean comma = false;

    protected final Writer w;
    protected final NumberFormat nf;

    {
        nf = NumberFormat.getNumberInstance(Locale.ROOT);
        nf.setMinimumFractionDigits(0);
        nf.setMaximumFractionDigits(20);
        nf.setGroupingUsed(false);
        if(nf instanceof DecimalFormat) {
            DecimalFormat df = (DecimalFormat) nf;
            df.setDecimalSeparatorAlwaysShown(false);
        }
    }

    UnboundedJSONBuilder(Writer w) {
        this.w = w;
    }

    protected UnboundedJSONBuilder append(String s) {
        switch(mode) {
            case 'i':
            case 'k':
            case 'o':
            case 'a':
                try {
                    (comma ? w.append(',') : w).append(s);
                    switch(mode) {
                        case 'i':
                            mode = 'd';
                            break;
                        case 'o':
                            mode = 'k';
                            comma = true;
                            break;
                        case 'k':
                            mode = 'o';
                            comma = false;
                            break;
                        case 'a':
                            comma = true;
                            break;
                    }
                } catch(IOException e) {
                    throw new JSONException(e);
                }
                return this;
            default:
                throw new JSONException("Value not expected here");
        }
    }

    protected UnboundedJSONBuilder append(char c) {
        switch(mode) {
            case 'i':
                mode = 'd';
                // fall-through
            case 'o':
                mode = 'k';
                // fall-through
            case 'a':
                try {
                    (comma ? w.append(',') : w).append(c);
                } catch(IOException e) {
                    throw new JSONException(e);
                }
                comma = true;
                break;
            default:
                throw new JSONException("Value not expected here");
        }
        return this;
    }

    protected UnboundedJSONBuilder write(char c) {
        try {
            w.write(c);
            return this;
        } catch(IOException e) {
            throw new JSONException(e);
        }
    }

    protected UnboundedJSONBuilder push(char mode) {
        if(top == stack.length) {
            stack = Arrays.copyOf(stack, stack.length * 2);
        }
        stack[top++] = mode;
        this.mode = mode;
        switch(mode) {
            case 'k':
            case 'a':
                comma = false;
        }
        return this;
    }

    protected UnboundedJSONBuilder pop() {
        --top;
        mode = top > 0 ? stack[top-1] : 'd';
        switch(mode) {
            case 'k':
            case 'a':
                comma = true;
        }
        return this;
    }

    public UnboundedJSONBuilder array() {
        switch(mode) {
            case 'i':
            case 'o':
            case 'a':
                append('[');
                return push('a');
            default:
                throw new JSONException("Invalid mode to start array: " + mode);
        }
    }

    public UnboundedJSONBuilder endArray() {
        switch(mode) {
            case 'a':
                write(']');
                return pop();
            default:
                throw new JSONException("Not in array");
        }
    }

    public UnboundedJSONBuilder object() {
        switch(mode) {
            case 'i':
            case 'o':
            case 'a':
                append('{');
                return push('k');
            default:
                throw new JSONException("Invalid mode to start object: " + mode);
        }
    }

    public UnboundedJSONBuilder endObject() {
        switch(mode) {
            case 'k':
                write('}');
                return pop();
            case 'o':
                throw new JSONException("Key without value");
            default:
                throw new JSONException("Not in object");
        }
    }

    public UnboundedJSONBuilder key(String k) {
        switch(mode) {
            case 'k':
                append(JSONUtils.quote(k));
                return write(':');
            default:
                throw new JSONException("Key not expected");
        }
    }

    public UnboundedJSONBuilder value(boolean o) {
        return append(o ? "true" : "false");
    }

    public UnboundedJSONBuilder value(double o) {
        return append(Double.isInfinite(o) || Double.isNaN(o) ? "null" : nf.format(o));
    }

    public UnboundedJSONBuilder value(long o) {
        return append(nf.format(o));
    }

    public UnboundedJSONBuilder value(Object o) {
        return append(JSONUtils.valueToString(o));
    }
}

class Demo {
    public static void main(String[] args) throws Exception {
        Writer w = new OutputStreamWriter(System.out);
        PrintWriter pw = new PrintWriter(w, true);
        UnboundedJSONBuilder b;

        new UnboundedJSONBuilder(w)
            .value("asdf");
        pw.println();

        new UnboundedJSONBuilder(w)
            .value(0);
        pw.println();

        new UnboundedJSONBuilder(w)
            .value(Math.PI);
        pw.println();

        new UnboundedJSONBuilder(w)
            .value(Double.POSITIVE_INFINITY);
        pw.println();

        new UnboundedJSONBuilder(w)
            .value(false);
        pw.println();

        new UnboundedJSONBuilder(w)
            .value(true);
        pw.println();

        new UnboundedJSONBuilder(w)
            .object()
                .key("asdf").value("zxcv")
                .key("qwer").value(1234)
                .key("yuio").value(true)
            .endObject();
        pw.println();

        new UnboundedJSONBuilder(w)
            .array()
                .value("asdf")
                .value(1234)
                .value(true)
                .value(false)
                .value(null)
                .array()
                    .value("asdf")
                    .value(1234)
                    .value(true)
                    .value(false)
                    .value(null)
                .endArray()
            .endArray();
        pw.println();

        new UnboundedJSONBuilder(w)
            .array()
                .value("asdf")
                .value(1234)
                .value(true)
                .value(false)
                .value(null)
                .object()
                    .key("asdf").value("zxcv")
                    .key("qwer").value(1234)
                    .key("yuio").value(true)
                    .key("foo").array()
                        .value("asdf")
                        .value(1234)
                        .value(true)
                        .value(false)
                        .value(null)
                    .endArray()
                    .key("bar").array()
                        .value("asdf")
                        .value(1234)
                        .value(true)
                        .value(false)
                        .value(null)
                    .endArray()
                    .key("hello world").object()
                        .key("asdf").value("zxcv")
                        .key("qwer").value(1234)
                        .key("yuio").value(true)
                    .endObject()
                .endObject()
            .endArray();
        pw.println();
    }
}