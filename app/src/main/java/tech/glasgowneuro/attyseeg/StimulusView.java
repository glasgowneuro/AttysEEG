package tech.glasgowneuro.attyseeg;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * Created by bp1 on 03/02/17.
 */

public class StimulusView extends View {

    boolean inverted = false;

    public StimulusView(Context context) {
        super(context);
    }

    public StimulusView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public StimulusView(Context context, AttributeSet attrs, int defStyle) {
        super(context,attrs,defStyle);
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

        Paint paint_white = new Paint();
        paint_white.setColor(Color.rgb(255, 255, 255));

        Paint paint_black = new Paint();
        paint_black.setColor(Color.rgb(0, 0, 0));

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
