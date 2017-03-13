package tech.glasgowneuro.attyseeg;

import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

/**
 * Calculates the screensize and determines if it's a mobile or not
 */

public class Screensize {

    final String TAG="Screensize";

    DisplayMetrics metrics = new DisplayMetrics();
    final double width;
    final double height;
    final float diagonal;

    Screensize(WindowManager windowManager) {
        windowManager.getDefaultDisplay().getMetrics(metrics);
        width = metrics.widthPixels / metrics.xdpi;
        height = metrics.heightPixels / metrics.ydpi;
        diagonal = (float)(Math.sqrt(width*width + height*height));
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "screensize=" + diagonal + "in");
        }
    }

    public boolean isMobile() {
        return (diagonal < 5);
    }

    public boolean isTablet() {
        return !(diagonal < 5);
    }

    public float sizeInInch() {
        return diagonal;
    }
}
