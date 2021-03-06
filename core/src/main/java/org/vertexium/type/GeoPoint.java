package org.vertexium.type;

import org.vertexium.VertexiumException;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.vertexium.util.Preconditions.checkArgument;
import static org.vertexium.util.Preconditions.checkNotNull;

public class GeoPoint extends GeoShapeBase implements Comparable<GeoPoint> {
    private static final long serialVersionUID = 1L;
    private static final double COMPARE_TOLERANCE = 0.0001;
    private static final Pattern HOUR_MIN_SECOND_PATTERN = Pattern.compile("\\s*(-)?([0-9\\.]+)°(\\s*([0-9\\.]+)'(\\s*([0-9\\.]+)\")?)?");
    private static final Pattern WITH_DESCRIPTION_PATTERN = Pattern.compile("(.*)\\[(.*)\\]");
    public static final double EQUALS_TOLERANCE_KM = 0.001;
    private double latitude;
    private double longitude;
    private Double altitude;

    /**
     * Create a geopoint at 0, 0 with an altitude of 0
     */
    protected GeoPoint() {
    }

    /**
     * @param latitude    latitude is specified in decimal degrees
     * @param longitude   longitude is specified in decimal degrees
     * @param altitude    altitude is specified in kilometers
     * @param description name or description of this shape
     */
    public GeoPoint(double latitude, double longitude, Double altitude, String description) {
        super(description);
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitude = altitude;
    }

    public GeoPoint(double latitude, double longitude, Double altitude) {
        this(latitude, longitude, altitude, null);
    }

    public GeoPoint(double latitude, double longitude) {
        this(latitude, longitude, null, null);
    }

    public GeoPoint(double latitude, double longitude, String description) {
        this(latitude, longitude, null, description);
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public Double getAltitude() {
        return altitude;
    }

    @Override
    public String toString() {
        String coords;
        if (getAltitude() != null) {
            coords = "(" + getLatitude() + ", " + getLongitude() + ", " + getAltitude() + ")";
        } else {
            coords = "(" + getLatitude() + ", " + getLongitude() + ")";
        }
        return coords + (getDescription() == null ? "" : ": " + getDescription());
    }

    @Override
    public boolean intersects(GeoShape geoShape) {
        if (geoShape instanceof GeoPoint) {
            return this.equals(geoShape);
        }
        return geoShape.intersects(this);
    }

    @Override
    public boolean within(GeoShape geoShape) {
        throw new VertexiumException("Not implemented for argument type " + geoShape.getClass().getName());
    }

    @Override
    public int hashCode() {
        int hash = 3;
        hash = 47 * hash + (int) (Double.doubleToLongBits(this.latitude) ^ (Double.doubleToLongBits(this.latitude) >>> 32));
        hash = 47 * hash + (int) (Double.doubleToLongBits(this.longitude) ^ (Double.doubleToLongBits(this.longitude) >>> 32));
        hash = 47 * hash + (this.altitude != null ? this.altitude.hashCode() : 0);
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        GeoPoint other = (GeoPoint) obj;
        double distanceBetween = distanceBetween(this, other);
        if (Double.isNaN(distanceBetween)) {
            return false;
        }
        if (Math.abs(distanceBetween) > EQUALS_TOLERANCE_KM) {
            return false;
        }
        if ((this.altitude != null && other.altitude == null) || (this.altitude == null && other.altitude != null)) {
            return false;
        }
        if (this.altitude != null && other.altitude != null && Math.abs(this.altitude - other.altitude) > EQUALS_TOLERANCE_KM) {
            return false;
        }
        return true;
    }

    public static double distanceBetween(GeoPoint geoPoint1, GeoPoint geoPoint2) {
        return geoPoint1.distanceFrom(geoPoint2);
    }

    public double distanceFrom(GeoPoint geoPoint) {
        return distanceBetween(
                this.getLatitude(), this.getLongitude(),
                geoPoint.getLatitude(), geoPoint.getLongitude()
        );
    }

    @Override
    public int compareTo(GeoPoint other) {
        int i;
        if ((i = compare(getLatitude(), other.getLatitude())) != 0) {
            return i;
        }
        if ((i = compare(getLongitude(), other.getLongitude())) != 0) {
            return i;
        }
        if (getAltitude() != null && other.getAltitude() != null) {
            return compare(getAltitude(), other.getAltitude());
        }
        if (getAltitude() != null) {
            return 1;
        }
        if (other.getAltitude() != null) {
            return -1;
        }
        return 0;
    }

    private static int compare(double d1, double d2) {
        if (Math.abs(d1 - d2) < COMPARE_TOLERANCE) {
            return 0;
        }
        if (d1 < d2) {
            return -1;
        }
        if (d1 > d2) {
            return 1;
        }
        return 0;
    }

    public static GeoPoint parse(String str) {
        String description;
        Matcher m = WITH_DESCRIPTION_PATTERN.matcher(str);
        if (m.matches()) {
            description = m.group(1).trim();
            str = m.group(2).trim();
        } else {
            description = null;
        }

        String[] parts = str.split(",");
        if (parts.length < 2) {
            throw new VertexiumException("Too few parts to GeoPoint string. Expected at least 2 found " + parts.length + " for string: " + str);
        }
        if (parts.length >= 4) {
            throw new VertexiumException("Too many parts to GeoPoint string. Expected less than or equal to 3 found " + parts.length + " for string: " + str);
        }
        double latitude = parsePart(parts[0]);
        double longitude = parsePart(parts[1]);
        Double altitude = null;
        if (parts.length >= 3) {
            altitude = Double.parseDouble(parts[2]);
        }
        return new GeoPoint(latitude, longitude, altitude, description);
    }

    private static double parsePart(String part) {
        Matcher m = HOUR_MIN_SECOND_PATTERN.matcher(part);
        if (m.matches()) {
            String deg = m.group(2);
            double result = Double.parseDouble(deg);
            if (m.groupCount() >= 4) {
                String minutes = m.group(4);
                result += Double.parseDouble(minutes) / 60.0;
                if (m.groupCount() >= 6) {
                    String seconds = m.group(6);
                    result += Double.parseDouble(seconds) / (60.0 * 60.0);
                }
            }
            if (m.group(1) != null && m.group(1).equals("-")) {
                result = -result;
            }
            return result;
        }
        return Double.parseDouble(part);
    }

    public boolean isSouthEastOf(GeoPoint pt) {
        return isSouthOf(pt) && isEastOf(pt);
    }

    private boolean isEastOf(GeoPoint pt) {
        return longitudinalDistanceTo(pt) > 0;
    }

    public boolean isSouthOf(GeoPoint pt) {
        return getLatitude() < pt.getLatitude();
    }

    public boolean isNorthWestOf(GeoPoint pt) {
        return isNorthOf(pt) && isWestOf(pt);
    }

    private boolean isWestOf(GeoPoint pt) {
        return longitudinalDistanceTo(pt) < 0;
    }

    public double longitudinalDistanceTo(GeoPoint pt) {
        double me = getLongitude();
        double them = pt.getLongitude();
        double result = Math.abs(me - them) > 180.0 ? (them - me) : (me - them);
        if (result > 180.0) {
            result -= 360.0;
        }
        if (result < -180.0) {
            result += 360.0;
        }
        return result;
    }

    public boolean isNorthOf(GeoPoint pt) {
        return getLatitude() > pt.getLatitude();
    }

    /**
     * For large distances center point calculation has rounding errors
     */
    public static GeoPoint calculateCenter(List<GeoPoint> geoPoints) {
        checkNotNull(geoPoints, "geoPoints cannot be null");
        checkArgument(geoPoints.size() > 0, "must have at least 1 geoPoints");
        if (geoPoints.size() == 1) {
            return geoPoints.get(0);
        }

        double x = 0.0;
        double y = 0.0;
        double z = 0.0;
        double totalAlt = 0.0;
        int altitudeCount = 0;
        for (GeoPoint geoPoint : geoPoints) {
            double latRad = Math.toRadians(geoPoint.getLatitude());
            double lonRad = Math.toRadians(geoPoint.getLongitude());
            x += Math.cos(latRad) * Math.cos(lonRad);
            y += Math.cos(latRad) * Math.sin(lonRad);
            z += Math.sin(latRad);

            if (geoPoint.getAltitude() != null) {
                totalAlt += geoPoint.getAltitude();
                altitudeCount++;
            }
        }

        x = x / (double) geoPoints.size();
        y = y / (double) geoPoints.size();
        z = z / (double) geoPoints.size();

        return new GeoPoint(
                Math.toDegrees(Math.atan2(z, Math.sqrt(x * x + y * y))),
                Math.toDegrees(Math.atan2(y, x)),
                altitudeCount == geoPoints.size() ? (totalAlt / (double) altitudeCount) : null
        );
    }
}
