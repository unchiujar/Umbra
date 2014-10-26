package org.unchiujar.umbra.overlays;

import android.app.Activity;
import android.graphics.*;
import android.location.Location;
import android.preference.PreferenceManager;
import android.util.Log;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Tile;
import com.google.android.gms.maps.model.TileProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.unchiujar.umbra.activities.Preferences;
import org.unchiujar.umbra.location.ApproximateLocation;
import org.unchiujar.umbra.utils.LocationUtilities;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static android.graphics.Color.BLACK;
import static android.graphics.Paint.ANTI_ALIAS_FLAG;
import static org.unchiujar.umbra.location.LocationOrder.METERS_RADIUS;

public class ExploredTileProvider implements TileProvider {
    private static final int SHADING_PASSES = 15;
    private static final String TAG = ExploredOverlay.class.getName();
    private static final Logger LOGGER = LoggerFactory.getLogger(ExploredTileProvider.class);
    /**
     * Transparency level of explored area. Lower value means more transparent.
     */
    public static final int TRANSPARENCY = 170;
    private static int mAlpha;
    private List<ApproximateLocation> mLocations;
    private Paint mRectPaint;

    private Paint mClearPaint;

    private Paint mShadePaint;


    private static final int TILE_SIZE = 256;

    private GoogleMap map;

    public ExploredTileProvider(Activity context, GoogleMap map) {
        LOGGER.debug("Tile overlay constructed with map {}", map);
        this.map = map;

        // ========== PAINTS SETUP =========
        mRectPaint = new Paint(ANTI_ALIAS_FLAG);
        mRectPaint.setColor(BLACK);
        mRectPaint.setStyle(Paint.Style.FILL_AND_STROKE);

        mClearPaint = new Paint(ANTI_ALIAS_FLAG);
        // set PorterDuff mode in order to create transparent holes in
        // the canvas
        // see http://developer.android.com/reference/android/graphics/PorterDuff.Mode.html
        // see http://en.wikipedia.org/wiki/Alpha_compositing
        // see http://groups.google.com/group/android-developers/browse_thread/thread/5b0a498664b17aa0/de4aab6fb7e97e38?lnk=gst&q=erase+transparent&pli=1
        mClearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        mClearPaint.setAlpha(255);
        mClearPaint.setColor(BLACK);
        mClearPaint.setStyle(Paint.Style.FILL_AND_STROKE);

        mShadePaint = new Paint(ANTI_ALIAS_FLAG);
        mShadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));

        mShadePaint.setColor(BLACK);
        mShadePaint.setStyle(Paint.Style.FILL);
        mShadePaint.setAlpha(TRANSPARENCY);


        mAlpha = 255 - PreferenceManager.getDefaultSharedPreferences(context).getInt(Preferences.TRANSPARENCY, 120);


        pixelOrigin_ = new WorldCoordinate(TILE_SIZE / 2, TILE_SIZE / 2);
        pixelsPerLonDegree_ = TILE_SIZE / 360d;
        pixelsPerLonRadian_ = TILE_SIZE / (2 * Math.PI);

    }

    @Override
    public Tile getTile(int x, int y, int zoom) {
        LOGGER.debug("Getting tile for coordinates {} {} and zoom {}", x, y, zoom);
        //create bitmap tile
        Bitmap image = draw(x, y, zoom);

        //create byte stream
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        image.compress(Bitmap.CompressFormat.PNG, 100, stream);

        //return tile
        return new Tile(TILE_SIZE, TILE_SIZE, stream.toByteArray());
    }

    /**
     * Get the dimensions of the Tile in LatLng coordinates
     */
    public LatLngBounds getTileBounds(int x, int y, int zoom) {
        int noTiles = (1 << zoom);
        double longitudeSpan = 360.0 / noTiles;
        double longitudeMin = -180.0 + x * longitudeSpan;

        double mercatorMax = 180 - (((double) y) / noTiles) * 360;
        double mercatorMin = 180 - (((double) y + 1) / noTiles) * 360;
        double latitudeMax = toLatitude(mercatorMax);
        double latitudeMin = toLatitude(mercatorMin);

        return new LatLngBounds(new LatLng(latitudeMin, longitudeMin), new LatLng(latitudeMax, longitudeMin + longitudeSpan));
    }

    public static double toLatitude(double mercator) {
        double radians = Math.atan(Math.exp(Math.toRadians(mercator)));
        return Math.toDegrees(2 * radians) - 90;
    }

    /**
     * Return value reduced to min and max if outside one of these bounds.
     */
    private double bound(double value, double min, double max) {
        value = Math.max(value, min);
        value = Math.min(value, max);
        return value;
    }


    /**
     * Get the coordinates in a system describing the whole globe in a
     * coordinate range from 0 to TILE_SIZE (type double).
     * <p/>
     * Takes the resulting point as parameter, to avoid creation of new objects.
     */
    private WorldCoordinate latLngToWorldCoordinates(LatLng latLng) {

        double x = pixelOrigin_.x + latLng.longitude * pixelsPerLonDegree_;

        // Truncating to 0.9999 effectively limits latitude to 89.189. This is
        // about a third of a tile past the edge of the world tile.
        double siny = bound(Math.sin(Math.toRadians(latLng.latitude)), -0.9999,
                0.9999);
        double y = pixelOrigin_.y + 0.5 * Math.log((1 + siny) / (1 - siny))
                * -pixelsPerLonRadian_;

        return new WorldCoordinate(x, y);
    }


    /**
     * Calculate the pixel coordinates inside a tile, relative to the left upper
     * corner (origin) of the tile.
     */
    public Point latLngToTilePoint(LatLng latLng, int x, int y, int zoom) {

        WorldCoordinate worldCoordinates = latLngToWorldCoordinates(latLng);
        Point pixelCoordinates = worldToPixelCoordinates(worldCoordinates, zoom);

        pixelCoordinates.x -= x * TILE_SIZE;
        pixelCoordinates.y -= y * TILE_SIZE;

        return pixelCoordinates;
    }

    /**
     * Transform the world coordinates into pixel-coordinates relative to the
     * whole tile-area. (i.e. the coordinate system that spans all tiles.)
     * <p/>
     * <p/>
     * Takes the resulting point as parameter, to avoid creation of new objects.
     */
    private Point worldToPixelCoordinates(WorldCoordinate worldCoord, int zoom) {
        int numTiles = 1 << zoom;
        return new Point((int) (worldCoord.x * numTiles), (int) (worldCoord.y * numTiles));
    }


    /**
     * A Point in an x/y coordinate system with coordinates of type double
     */
    public static class WorldCoordinate {
        double x;
        double y;

        public WorldCoordinate(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }


    public Bitmap draw(int x, int y, int zoom) {
        LOGGER.debug("Drawing tile...");


        LatLngBounds bounds = getTileBounds(x, y, zoom);

        LOGGER.debug("Tile bounds are {}", bounds);
        int passes = zoom < 14 ? 1 : zoom - 8;
        LOGGER.debug("Shading passes {}  ", passes);

        // zoom level, in the range of 2.0 to 21.0. Values below this range are set to 2.0,
        // and values above it are set to 21.0. Increase the value to zoom in.
        // Not all areas have tiles at the largest zoom levels.

        // the size of the displayed area is dependent on the zoom level
        // 2 - 21 levels

        final double pixelsMeter = pixelsPerMeter(TILE_SIZE,
                new LatLng(bounds.southwest.latitude, bounds.southwest.longitude),
                new LatLng(bounds.northeast.latitude, bounds.southwest.longitude));
        int radius = (int) (METERS_RADIUS * pixelsMeter);

        radius = (radius <= 3) ? 3 : radius;

        LOGGER.debug("View distance is {} meters, radius in pixels is {} pixel per meter is {}", METERS_RADIUS, radius, pixelsMeter);


        Bitmap bitmap = Bitmap.createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        // TODO check is width, height is always the same - rotation may be a problem
        Rect mScreenCover = new Rect(0, 0, TILE_SIZE, TILE_SIZE);


        canvas.drawRect(mScreenCover, mRectPaint);
        LOGGER.debug("Processing locations {}", mLocations);
        for (ApproximateLocation location : mLocations) {
            // Log.v(TAG, "GeoPoint to screen point: " + mTempPoint);
            // for display use only visible points
            if (bounds.contains(new LatLng(location.getLatitude(), location.getLongitude()))) {
                drawShadedDisc(radius, passes, latLngToTilePoint(LocationUtilities.locationToLatLng(location), x, y, zoom), canvas);
            }

        }

        mRectPaint.setAlpha(mAlpha);

        canvas.drawBitmap(bitmap, 0, 0, mRectPaint);


        return bitmap;
    }


    private WorldCoordinate pixelOrigin_;
    private double pixelsPerLonDegree_;
    private double pixelsPerLonRadian_;


    public void setExplored(List<ApproximateLocation> locations) {
        this.mLocations = locations;
        Log.d(TAG, "Explored size is: " + this.mLocations.size());
    }


    public double pixelsPerMeter(int width, LatLng start, LatLng stop) {
        //calculate distance in meters
        float[] results = new float[3];
        Location.distanceBetween(start.latitude, start.longitude, stop.latitude, stop.longitude, results);
        return width / results[0];
    }

    /**
     * Calculate shading passes from zoom level
     * if the map is zoom out do not try to shade so much
     * shading passes are equivalent to zoom level minus a constant
     * after a certain zoom level only clear the area instead of
     * shading
     */

    private void drawShadedDisc(int radius, int passes, Point currentPoint, Canvas canvas) {
        int x = currentPoint.x;
        int y = currentPoint.y;
        // if the passes are only one do not shade, just clear
        if (passes == 1) {
            canvas.drawCircle(x, y, radius, mClearPaint);
            return;
        }

        for (int i = 0; i < passes; i++) {
            canvas.drawCircle(x, y, (SHADING_PASSES - i) * radius / SHADING_PASSES * 0.8f + radius * 0.2f, mShadePaint);
        }
    }
}
