package tech.glasgowneuro.attyseeg;

import android.Manifest;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.Fragment;
import android.support.v7.app.AlertDialog;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.androidplot.xy.BoundaryMode;
import com.androidplot.xy.LineAndPointFormatter;
import com.androidplot.xy.SimpleXYSeries;
import com.androidplot.xy.StepMode;
import com.androidplot.xy.XYPlot;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Timer;
import java.util.TimerTask;

import tech.glasgowneuro.attyscomm.AttysComm;
import uk.me.berndporr.iirj.Bessel;
import uk.me.berndporr.iirj.Butterworth;

/**
 * Created by bp1 on 31/01/17.
 */

public class FastSlowRatioFragment extends Fragment {

    String TAG = "FastSlowRatioFragment";

    String title = "log(Power of Fast/Slow)";

    float bandLow = 40;
    float bandHigh = 200;
    float allLow = 10;
    float allHigh = 47;
    float smoothFreq = 0.1F;

    final int nSampleBufferSize = 100;

    private final int REFRESH_IN_MS = 500;

    double fastSlowRatio = 0;

    private SimpleXYSeries fastSlowHistorySeries = null;

    private XYPlot fastSlowPlot = null;

    private TextView fastSlowReadingText = null;

    private ToggleButton toggleButtonDoRecord;

    private Button resetButton;

    private Button saveButton;

    View view = null;

    Butterworth highpassFilterEEGband = null;
    Butterworth lowpassFilterEEGband = null;
    Butterworth highpassfilterEEGall = null;
    Butterworth lowpassFilterEEGall = null;
    Bessel smoothBand = null;
    Bessel smoothAll = null;

    int samplingRate = 250;

    int step = 0;

    double delta_t = (double) REFRESH_IN_MS / 1000.0;

    boolean ready = false;

    boolean acceptData = false;

    Timer timer = null;

    private String dataFilename = null;

    private byte dataSeparator = AttysComm.DATA_SEPARATOR_TAB;

    public void setSamplingrate(int _samplingrate) {
        samplingRate = _samplingrate;
    }

    public void stopPlotting() {
        if (timer != null) {
            timer.cancel();
        }
        if (toggleButtonDoRecord != null) {
            toggleButtonDoRecord.setChecked(false);
        }
        timer = null;
        acceptData = false;
    }

    private void reset() {
        ready = false;

        step = 0;

        highpassFilterEEGband = new Butterworth();
        lowpassFilterEEGband = new Butterworth();
        highpassfilterEEGall = new Butterworth();
        lowpassFilterEEGall = new Butterworth();
        smoothBand = new Bessel();
        smoothAll = new Bessel();

        highpassFilterEEGband.highPass(2, samplingRate, bandLow);
        lowpassFilterEEGband.lowPass(2, samplingRate, bandHigh);
        highpassfilterEEGall.highPass(2, samplingRate, allLow);
        lowpassFilterEEGall.lowPass(2, samplingRate, allHigh);
        smoothBand.lowPass(2, samplingRate, smoothFreq);
        smoothAll.lowPass(2, samplingRate, smoothFreq);

        fastSlowPlot.addSeries(fastSlowHistorySeries,
                new LineAndPointFormatter(
                        Color.rgb(100, 255, 255), null, null, null));

        fastSlowPlot.setDomainLabel("t/sec");
        fastSlowPlot.setRangeLabel("");

        DisplayMetrics metrics = new DisplayMetrics();
        getActivity().getWindowManager().getDefaultDisplay().getMetrics(metrics);
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;

        if ((height > 1000) && (width > 1000)) {
            fastSlowPlot.setDomainStep(StepMode.INCREMENT_BY_VAL, 10);
        } else {
            fastSlowPlot.setDomainStep(StepMode.INCREMENT_BY_VAL, 30);
        }

        ready = true;
    }


    private void resetPlot() {
        step = 0;
        int n = fastSlowHistorySeries.size();
        for (int i = 0; i < n; i++) {
            fastSlowHistorySeries.removeLast();
        }
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

        view = inflater.inflate(R.layout.fastslowfragment, container, false);

        // setup the APR Levels plot:
        fastSlowPlot = (XYPlot) view.findViewById(R.id.fastslowPlotView);
        fastSlowReadingText = (TextView) view.findViewById(R.id.fastslow_valueTextView);
        fastSlowReadingText.setText(String.format("%04d", 0));
        toggleButtonDoRecord = (ToggleButton) view.findViewById(R.id.fastSlow_doRecord);
        toggleButtonDoRecord.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    resetPlot();
                    acceptData = true;
                    timer = new Timer();
                    UpdatePlotTask updatePlotTask = new UpdatePlotTask();
                    timer.schedule(updatePlotTask, 0, REFRESH_IN_MS);
                }
            }
        });
        resetButton = (Button) view.findViewById(R.id.fastSlowReset);
        resetButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                resetPlot();
            }
        });
        saveButton = (Button) view.findViewById(R.id.fastSlowSave);
        saveButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                saveBetaRatio();
            }
        });


        fastSlowHistorySeries = new SimpleXYSeries(title);
        if (fastSlowHistorySeries == null) {
            if (Log.isLoggable(TAG, Log.ERROR)) {
                Log.e(TAG, "fastSlowHistorySeries == null");
            }
        }
        // fastSlowPlot.setDomainBoundaries(0,(double)nSampleBufferSize,BoundaryMode.FIXED);

        Paint paint = new Paint();
        paint.setColor(Color.argb(128, 0, 255, 0));
        fastSlowPlot.getGraph().setDomainGridLinePaint(paint);
        fastSlowPlot.getGraph().setRangeGridLinePaint(paint);

        reset();

        return view;

    }


    private void writeBetaRatiofile() throws IOException {

        PrintWriter aepdataFileStream;

        if (dataFilename == null) return;

        File file;

        try {
            file = new File(AttysEEG.ATTYSDIR, dataFilename.trim());
            file.createNewFile();
            Log.d(TAG, "Saving fast/slow data to " + file.getAbsolutePath());
            aepdataFileStream = new PrintWriter(file);
        } catch (java.io.FileNotFoundException e) {
            throw e;
        }

        char s = ' ';
        switch (dataSeparator) {
            case AttysComm.DATA_SEPARATOR_SPACE:
                s = ' ';
                break;
            case AttysComm.DATA_SEPARATOR_COMMA:
                s = ',';
                break;
            case AttysComm.DATA_SEPARATOR_TAB:
                s = 9;
                break;
        }

        for (int i = 0; i < fastSlowHistorySeries.size(); i++) {
            aepdataFileStream.format("%e%c%e%c\n",
                    fastSlowHistorySeries.getX(i), s,
                    fastSlowHistorySeries.getY(i), s);
            if (aepdataFileStream.checkError()) {
                throw new IOException("file write error");
            }
        }

        aepdataFileStream.close();

        Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        Uri contentUri = Uri.fromFile(file);
        mediaScanIntent.setData(contentUri);
        getActivity().sendBroadcast(mediaScanIntent);
    }


    private void saveBetaRatio() {

        final EditText filenameEditText = new EditText(getContext());
        filenameEditText.setSingleLine(true);

        final int REQUEST_EXTERNAL_STORAGE = 1;
        String[] PERMISSIONS_STORAGE = {
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        };

        int permission = ActivityCompat.checkSelfPermission(getContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE);

        if (permission != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    getActivity(),
                    PERMISSIONS_STORAGE,
                    REQUEST_EXTERNAL_STORAGE
            );
        }

        filenameEditText.setHint("");
        filenameEditText.setText(dataFilename);

        new AlertDialog.Builder(getContext())
                .setTitle("Saving fast/slow data")
                .setMessage("Enter the filename of the data textfile")
                .setView(filenameEditText)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        dataFilename = filenameEditText.getText().toString();
                        dataFilename = dataFilename.replaceAll("[^a-zA-Z0-9.-]", "_");
                        if (!dataFilename.contains(".")) {
                            switch (dataSeparator) {
                                case AttysComm.DATA_SEPARATOR_COMMA:
                                    dataFilename = dataFilename + ".csv";
                                    break;
                                case AttysComm.DATA_SEPARATOR_SPACE:
                                    dataFilename = dataFilename + ".dat";
                                    break;
                                case AttysComm.DATA_SEPARATOR_TAB:
                                    dataFilename = dataFilename + ".tsv";
                            }
                        }
                        try {
                            writeBetaRatiofile();
                            Toast.makeText(getActivity(),
                                    "Successfully written '" + dataFilename + "' to the external memory",
                                    Toast.LENGTH_SHORT).show();
                        } catch (IOException e) {
                            Toast.makeText(getActivity(),
                                    "Write Error while saving '" + dataFilename + "' to the external memory",
                                    Toast.LENGTH_SHORT).show();
                            Log.d(TAG, "Error saving file: ", e);
                        }
                        ;
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                    }
                })
                .show();
    }


    public synchronized void addValue(final float v) {
        if (!ready) return;
        double band = lowpassFilterEEGband.filter(highpassFilterEEGband.filter((double) v));
        band = band * band;
        band = smoothBand.filter(band);
        if (band < 0) band = 0;
        double all = lowpassFilterEEGall.filter(highpassfilterEEGall.filter((double) v));
        all = all * all;
        all = smoothAll.filter(all);
        if (all < 0) all = 0;
        if ((all != 0) && (band != 0)){
            fastSlowRatio = Math.log10(band / all);
        }
    }


    private class UpdatePlotTask extends TimerTask {

        public synchronized void run() {

            if (!ready) return;

            if (!acceptData) return;

            if (getActivity() != null) {
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (fastSlowReadingText != null) {
                            fastSlowReadingText.setText(String.format("%04f", fastSlowRatio));
                        }
                    }
                });
            }

            if (fastSlowHistorySeries == null) {
                if (Log.isLoggable(TAG, Log.VERBOSE)) {
                    Log.v(TAG, "fastSlowHistorySeries == null");
                }
                return;
            }

            // get rid the oldest sample in history:
            if (fastSlowHistorySeries.size() > nSampleBufferSize) {
                fastSlowHistorySeries.removeFirst();
            }

            int n = nSampleBufferSize - fastSlowHistorySeries.size();
            for(int i=0;i<n;i++) {
                // add the latest history sample:
                fastSlowHistorySeries.addLast(step*delta_t, fastSlowRatio);
                step++;
            }

            // add the latest history sample:
            fastSlowHistorySeries.addLast(step*delta_t, fastSlowRatio);
            step++;
            //Log.v(TAG, "fastSlowRatio=" + fastSlowRatio);
            //Log.v(TAG, "size="+fastSlowHistorySeries.size());
            fastSlowPlot.redraw();

        }
    }
}
