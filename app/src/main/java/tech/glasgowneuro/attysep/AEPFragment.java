package tech.glasgowneuro.attysep;

import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.ToggleButton;

import com.androidplot.xy.BoundaryMode;
import com.androidplot.xy.LineAndPointFormatter;
import com.androidplot.xy.SimpleXYSeries;
import com.androidplot.xy.XYPlot;

/**
 * Created by Bernd Porr on 20/01/17.
 * <p>
 * Heartrate Plot
 */

public class AEPFragment extends Fragment {

    String TAG = "AEPFragment";

    private static final int HISTORY_SIZE = 60;

    private SimpleXYSeries bpmHistorySeries = null;

    private XYPlot bpmPlot = null;

    private TextView bpmText = null;

    private ToggleButton toggleButtonDoSweep;

    View view = null;

    int nSamples = 250 / 2;

    int samplingRate = 250;

    int index = 0;

    int nSweeps = 1;

    boolean ready = false;

    boolean doSweeps = false;

    boolean acceptData = false;

    public void setSamplingrate(int _samplingrate) {
        samplingRate = _samplingrate;
    }

    private void reset() {
        ready = false;
        nSamples = samplingRate / 2;
        float tmax = nSamples * (1.0F / ((float) samplingRate));
        //bpmPlot.setRangeBoundaries(-10, 10, BoundaryMode.FIXED);
        bpmPlot.setDomainBoundaries(0, tmax, BoundaryMode.FIXED);
        bpmPlot.addSeries(bpmHistorySeries,
                new LineAndPointFormatter(
                        Color.rgb(100, 255, 255), null, null, null));
        bpmPlot.setDomainLabel("t/sec");
        bpmPlot.setRangeLabel("");

        for (int i = 0; i < nSamples; i++) {
            bpmHistorySeries.addLast(i * (1.0F / ((float) samplingRate)), 0.0);
        }

        index = 0;
        nSweeps = 1;

        /**
         DisplayMetrics metrics = new DisplayMetrics();
         getActivity().getWindowManager().getDefaultDisplay().getMetrics(metrics);
         int width = metrics.widthPixels;
         int height = metrics.heightPixels;
         if ((height > 1000) && (width > 1000)) {
         bpmPlot.setDomainStep(StepMode.INCREMENT_BY_VAL, 25);
         bpmPlot.setRangeStep(StepMode.INCREMENT_BY_VAL, 25);
         } else {
         bpmPlot.setDomainStep(StepMode.INCREMENT_BY_VAL, 50);
         bpmPlot.setRangeStep(StepMode.INCREMENT_BY_VAL, 50);
         }
         **/

        ready = true;
    }

    /**
     * Called when the activity is first created.
     */
    @Override
    public View onCreateView(LayoutInflater inflater,
                             ViewGroup container,
                             Bundle savedInstanceState) {

        Log.d(TAG, "onCreate, creating Fragment");

        if (container == null) {
            return null;
        }

        view = inflater.inflate(R.layout.eapfragment, container, false);

        // setup the APR Levels plot:
        bpmPlot = (XYPlot) view.findViewById(R.id.bpmPlotView);
        bpmText = (TextView) view.findViewById(R.id.bpmTextView);
        toggleButtonDoSweep = (ToggleButton) view.findViewById(R.id.doSweeps);
        toggleButtonDoSweep.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                doSweeps = isChecked;
                if (doSweeps) {
                    acceptData = true;
                }
            }
        });

        bpmHistorySeries = new SimpleXYSeries("AEP/uV");
        if (bpmHistorySeries == null) {
            if (Log.isLoggable(TAG, Log.ERROR)) {
                Log.e(TAG, "bpmHistorySeries == null");
            }
        }

        Paint paint = new Paint();
        paint.setColor(Color.argb(128, 0, 255, 0));
        bpmPlot.getGraph().setDomainGridLinePaint(paint);
        bpmPlot.getGraph().setRangeGridLinePaint(paint);

        reset();

        return view;

    }

    public synchronized void addValue(final float v) {

        if (!ready) return;

        if (!acceptData) return;

        if (getActivity() != null) {
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (bpmText != null) {
                        bpmText.setText(String.format("%04d sweeps", nSweeps));
                    }
                }
            });
        }

        if (bpmHistorySeries == null) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "bpmHistorySeries == null");
            }
            return;
        }

        double avg = bpmHistorySeries.getY(index).doubleValue();
        double v2 = v * 1E6;
        //avg = (avg + new_value)/2;
        double nSweepsD = (double) nSweeps;
        avg = ((nSweepsD - 1) / nSweepsD) * avg + (1 / nSweepsD) * v2;
        // Log.d(TAG,"avg="+avg);
        if (index < bpmHistorySeries.size()) {
            bpmHistorySeries.setY(avg, index);
        }
        index++;
        if (index >= nSamples) {
            index = 0;
            nSweeps++;
            if (!doSweeps) {
                acceptData = false;
            }
        }
    }

    public void redraw() {
        if (bpmPlot != null) {
            bpmPlot.redraw();
        }
    }
}
