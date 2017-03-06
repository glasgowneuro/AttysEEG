package tech.glasgowneuro.attyseeg;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import com.androidplot.xy.BarFormatter;
import com.androidplot.xy.BarRenderer;
import com.androidplot.xy.BoundaryMode;
import com.androidplot.xy.SimpleXYSeries;
import com.androidplot.xy.StepMode;
import com.androidplot.xy.XYGraphWidget;
import com.androidplot.xy.XYPlot;


import uk.me.berndporr.iirj.Butterworth;

/**
 * Shows a histogram of the EEG amplitudes
 */

public class BarGraphFragment extends Fragment {

    String TAG = "BarGraphFragment";

    public static final String[] string_bargraph_modes = {
            "Absolute Amplitudes (\u03bcV)",
            "Normalised Amplitudes"
    };

    final static int DELTA_INDEX = 0;
    final static int THETA_INDEX = 1;
    final static int ALPHA_INDEX = 2;
    final static int BETA_INDEX = 3;
    final static int GAMMA_INDEX = 4;

    float smoothFreq = 0.1F;

    private SimpleXYSeries barSeries = null;

    private XYPlot barPlot = null;

    View view = null;

    float samplingRate = 250;

    private Spinner spinnerMode;

    int mode = 0;

    void setSamplingRate(float _samplingrate) {
        samplingRate = _samplingrate;
    }

    Butterworth smoothAlpha = null;
    Butterworth smoothDelta = null;
    Butterworth smoothGamma = null;
    Butterworth smoothBeta = null;
    Butterworth smoothTheta = null;

    int refreshCounter = 1;

    boolean ready = false;

    /**
     * Called when the activity is first created.
     */
    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container,
                             Bundle savedInstanceState) {

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "creating Fragment");
        }

        if (container == null) {
            return null;
        }

        view = inflater.inflate(R.layout.bargraphfragment, container, false);

        // setup the APR Levels plot:
        barPlot = (XYPlot) view.findViewById(R.id.bargraphPlotView);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(),
                android.R.layout.simple_spinner_dropdown_item,
                string_bargraph_modes);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerMode = (Spinner) view.findViewById(R.id.bargraph_mode);
        spinnerMode.setAdapter(adapter);
        spinnerMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                mode = position;
                if (mode == 0) {
                    barPlot.setRangeLowerBoundary(0,BoundaryMode.FIXED);
                    barPlot.setRangeUpperBoundary(50,BoundaryMode.AUTO);
                    barPlot.setRangeLabel("\u03bcV");
                } else {
                    barPlot.setRangeLowerBoundary(0,BoundaryMode.FIXED);
                    barPlot.setRangeUpperBoundary(1,BoundaryMode.AUTO);
                    barPlot.setRangeLabel("normalised");
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        spinnerMode.setBackgroundResource(android.R.drawable.btn_default);

        barSeries = new SimpleXYSeries("amplitudes");
        for (int i = 0; i <= GAMMA_INDEX; i++) {
            barSeries.addLast(i, 0);
        }
        if (barSeries == null) {
            if (Log.isLoggable(TAG, Log.ERROR)) {
                Log.e(TAG, "barSeries == null");
            }
        }

        Paint paint = new Paint();
        paint.setColor(Color.argb(128, 0, 255, 0));
        barPlot.getGraph().setDomainGridLinePaint(paint);
        barPlot.getGraph().setRangeGridLinePaint(paint);
        barPlot.setDomainBoundaries(0, GAMMA_INDEX+0.5, BoundaryMode.FIXED);
        barPlot.setRangeBoundaries(0, 50, BoundaryMode.GROW);
        barPlot.setDomainStep(StepMode.INCREMENT_BY_VAL, 1);
        barPlot.setDomainLabel(" ");

        XYGraphWidget.LineLabelRenderer lineLabelRenderer = new XYGraphWidget.LineLabelRenderer() {
            @Override
            public void drawLabel(Canvas canvas, XYGraphWidget.LineLabelStyle style, Number val, float x, float y, boolean isOrigin) {
                final int canvasState = canvas.save();
                try {
                    String txt = "";
                    switch (val.intValue()) {
                        case 0:
                            txt = "\u03b4"; // delta
                            break;
                        case 1:
                            txt = "\u03b8"; // theta
                            break;
                        case 2:
                            txt = "\u03b1"; // alpha
                            break;
                        case 3:
                            txt = "\u03b2"; // beta
                            break;
                        case 4:
                            txt = "\u03b3"; // gamma
                            break;
                    }
                    Rect bounds = new Rect();
                    style.getPaint().getTextBounds("a", 0, 1, bounds);
                    drawLabel(canvas, txt, style.getPaint(), x+bounds.width()/2, y+bounds.height(), isOrigin);
                } finally {
                    canvas.restoreToCount(canvasState);
                }
            }
        };

        barPlot.getGraph().setLineLabelRenderer(XYGraphWidget.Edge.BOTTOM,lineLabelRenderer);

        BarFormatter barFormatter = new BarFormatter(Color.CYAN,Color.CYAN);

        barPlot.addSeries(barSeries, barFormatter);

        BarRenderer renderer = barPlot.getRenderer(BarRenderer.class);
        renderer.setBarGroupWidth(BarRenderer.BarGroupWidthMode.FIXED_GAP,1);

        //DisplayMetrics metrics = new DisplayMetrics();
        //getActivity().getWindowManager().getDefaultDisplay().getMetrics(metrics);
        //int width = metrics.widthPixels;
        //int height = metrics.heightPixels;

        smoothAlpha = new Butterworth();
        smoothDelta = new Butterworth();
        smoothGamma = new Butterworth();
        smoothBeta = new Butterworth();
        smoothTheta = new Butterworth();

        smoothAlpha.lowPass(2, samplingRate, smoothFreq);
        smoothDelta.lowPass(2, samplingRate, smoothFreq);
        smoothGamma.lowPass(2, samplingRate, smoothFreq);
        smoothBeta.lowPass(2, samplingRate, smoothFreq);
        smoothTheta.lowPass(2, samplingRate, smoothFreq);

        ready = true;

        return view;
    }


    public void onDestroy() {
        super.onDestroy();
        ready = false;
    }


    void addValue(double _delta, double _theta, double _alpha, double _beta, double _gamma) {

        if (!ready) return;

        double uv = 1000000;
        double delta = smoothDelta.filter(Math.sqrt(_delta * _delta)) * uv;
        double theta = smoothTheta.filter(Math.sqrt(_theta * _theta)) * uv;
        double alpha = smoothAlpha.filter(Math.sqrt(_alpha * _alpha)) * uv;
        double beta = smoothBeta.filter(Math.sqrt(_beta * _beta)) * uv;
        double gamma = smoothGamma.filter(Math.sqrt(_gamma * _gamma)) * uv;
        if (delta<0) delta = 0;
        if (theta<0) theta = 0;
        if (alpha<0) alpha = 0;
        if (beta<0) beta = 0;
        if (gamma<0) gamma = 0;

        refreshCounter--;
        if (refreshCounter < 1) {
            refreshCounter = (int) samplingRate / 2;
            if (mode == 1) {
                double sum = delta + theta + alpha + beta + gamma;
                delta = delta / sum;
                theta = theta / sum;
                alpha = alpha / sum;
                beta = beta / sum;
                gamma = gamma / sum;
            }
            barSeries.setY(delta, DELTA_INDEX);
            barSeries.setY(theta, THETA_INDEX);
            barSeries.setY(alpha, ALPHA_INDEX);
            barSeries.setY(beta, BETA_INDEX);
            barSeries.setY(gamma, GAMMA_INDEX);
            barPlot.redraw();
        }
    }
}
