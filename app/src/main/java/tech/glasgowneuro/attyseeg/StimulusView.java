package tech.glasgowneuro.attyseeg;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Creates the checkerboard stimulus
 */

public class StimulusView extends View {

    boolean inverted = false;
    Paint paint_white;
    Paint paint_black;

    private void doInit() {
        paint_white = new Paint();
        paint_white.setColor(Color.rgb(255, 255, 255));

        paint_black = new Paint();
        paint_black.setColor(Color.rgb(0, 0, 0));
    }

    public StimulusView(Context context) {
        super(context);
        doInit();
    }

    public StimulusView(Context context, AttributeSet attrs) {
        super(context, attrs);
        doInit();
    }

    public StimulusView(Context context, AttributeSet attrs, int defStyle) {
        super(context,attrs,defStyle);
        doInit();
    }

    public void setInverted(boolean _inverted) {
        inverted = _inverted;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = canvas.getWidth();
        int h = canvas.getHeight();
        int dx = w/10;
        int dy = w/10;

        boolean start_white = inverted;
        for(int y = 0;y<h;y = y + dy) {
            boolean white = start_white;
            for(int x = 0;x<w; x = x + dx) {
                if (white) {
                    canvas.drawRect(x, y, x + dx, y + dy, paint_white);
                } else {
                    canvas.drawRect(x,y,x+dx,y+dy,paint_black);
                }
                white = !white;
            }
            start_white = !start_white;
        }
    }
}
