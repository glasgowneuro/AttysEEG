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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.androidplot.xy.LineAndPointFormatter;
import com.androidplot.xy.SimpleXYSeries;
import com.androidplot.xy.StepMode;
import com.androidplot.xy.XYPlot;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Timer;
import java.util.TimerTask;

import uk.me.berndporr.iirj.Butterworth;

/**
 * Displays FastSlow and Beta ratio
 */

public class FastSlowRatioFragment extends Fragment {

    String TAG = "FastSlowRatioFragment";

    public static final String[] string_fastslow_modes = {
            "FastSlow",
            "BetaRatio"
    };

    int mode = 0;

    float bandLow[] = {
            40,     // fast/slow
            30      // beta ratio
    };
    float bandHigh[] = {
            100,    // flast/slow
            47      // beta ratio
    };
    float allLow[] = {
            0.5F,      // fast/slow
            11      // beta ratio
    };
    float allHigh[] = {
            47,     // fast/slow
            20      // beta ratio
    };
    float smoothFreq[] = {
            0.01F,
            0.1F
    };

    final int nSampleBufferSize = 100;

    private final int REFRESH_IN_MS = 500;

    double fastSlowRatio = 0;

    private SimpleXYSeries fastSlowHistorySeries = null;
    private SimpleXYSeries fastSlowFullSeries = null;

    private XYPlot fastSlowPlot = null;

    private TextView fastSlowReadingText = null;

    private ToggleButton toggleButtonDoRecord;

    private Button resetButton;

    private Button saveButton;

    private Spinner spinnerMode;

    View view = null;

    Butterworth highpassFilterEEGband = null;
    Butterworth lowpassFilterEEGband = null;
    Butterworth highpassfilterEEGall = null;
    Butterworth lowpassFilterEEGall = null;
    Butterworth smoothBand = null;
    Butterworth smoothAll = null;

    int samplingRate = 250;

    int step = 0;

    double delta_t = (double) REFRESH_IN_MS / 1000.0;

    boolean ready = false;

    boolean acceptData = false;

    Timer timer = null;

    private String dataFilename = null;

    private byte dataSeparator = AttysEEG.DataRecorder.DATA_SEPARATOR_TAB;

    public void setSamplingrate(int _samplingrate) {
        samplingRate = _samplingrate;
    }

    private void reset() {
        ready = false;

        step = 0;

        int n = fastSlowHistorySeries.size();
        for (int i = 0; i < n; i++) {
            fastSlowHistorySeries.removeLast();
        }
        fastSlowFullSeries = new SimpleXYSeries("");

        fastSlowHistorySeries.setTitle(string_fastslow_modes[mode]);
        fastSlowPlot.setTitle(string_fastslow_modes[mode]);

        fastSlowPlot.redraw();

        highpassFilterEEGband = new Butterworth();
        lowpassFilterEEGband = new Butterworth();
        highpassfilterEEGall = new Butterworth();
        lowpassFilterEEGall = new Butterworth();
        smoothBand = new Butterworth();
        smoothAll = new Butterworth();

        highpassFilterEEGband.highPass(2, samplingRate, bandLow[mode]);
        lowpassFilterEEGband.lowPass(2, samplingRate, bandHigh[mode]);
        highpassfilterEEGall.highPass(2, samplingRate, allLow[mode]);
        lowpassFilterEEGall.lowPass(2, samplingRate, allHigh[mode]);
        smoothBand.lowPass(2, samplingRate, smoothFreq[mode]);
        smoothAll.lowPass(2, samplingRate, smoothFreq[mode]);

        ready = true;
    }


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

        view = inflater.inflate(R.layout.fastslowfragment, container, false);

        // setup the APR Levels plot:
        fastSlowPlot = (XYPlot) view.findViewById(R.id.fastslowPlotView);
        fastSlowReadingText = (TextView) view.findViewById(R.id.fastslow_valueTextView);
        fastSlowReadingText.setText(String.format("%04d", 0));
        toggleButtonDoRecord = (ToggleButton) view.findViewById(R.id.fastSlow_doRecord);
        toggleButtonDoRecord.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    acceptData = true;
                    timer = new Timer();
                    UpdatePlotTask updatePlotTask = new UpdatePlotTask();
                    timer.schedule(updatePlotTask, 0, REFRESH_IN_MS);
                } else {
                    acceptData = false;
                    timer.cancel();
                }
            }
        });
        toggleButtonDoRecord.setChecked(true);
        resetButton = (Button) view.findViewById(R.id.fastSlowReset);
        resetButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                reset();
            }
        });
        saveButton = (Button) view.findViewById(R.id.fastSlowSave);
        saveButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                saveBetaRatio();
            }
        });
        spinnerMode = (Spinner) view.findViewById(R.id.fastSlow_mode);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(),
                android.R.layout.simple_spinner_dropdown_item,
                string_fastslow_modes);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerMode.setAdapter(adapter);
        spinnerMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (mode != position) {
                    Toast.makeText(getActivity(),
                            "Press RESET to confirm to record " + string_fastslow_modes[mode],
                            Toast.LENGTH_SHORT).show();
                }
                mode = position;
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        spinnerMode.setBackgroundResource(android.R.drawable.btn_default);

        fastSlowHistorySeries = new SimpleXYSeries("log(ratio)");
        if (fastSlowHistorySeries == null) {
            if (Log.isLoggable(TAG, Log.ERROR)) {
                Log.e(TAG, "fastSlowHistorySeries == null");
            }
        }

        Paint paint = new Paint();
        paint.setColor(Color.argb(128, 0, 255, 0));
        fastSlowPlot.getGraph().setDomainGridLinePaint(paint);
        fastSlowPlot.getGraph().setRangeGridLinePaint(paint);

        fastSlowPlot.addSeries(fastSlowHistorySeries,
                new LineAndPointFormatter(
                        Color.rgb(100, 255, 255), null, null, null));

        fastSlowPlot.setDomainLabel("t/sec");
        fastSlowPlot.setRangeLabel("");

        Screensize screensize = new Screensize(getActivity().getWindowManager());

        if (screensize.isTablet()) {
            fastSlowPlot.setDomainStep(StepMode.INCREMENT_BY_VAL, 10);
        } else {
            fastSlowPlot.setDomainStep(StepMode.INCREMENT_BY_VAL, 20);
        }

        fastSlowHistorySeries.setTitle(string_fastslow_modes[mode]);

        reset();

        return view;

    }


    private void writeSlowFastRatiofile() throws IOException {

        PrintWriter aepdataFileStream;

        if (dataFilename == null) return;

        File file;

        try {
            file = new File(AttysEEG.ATTYSDIR, dataFilename.trim());
            file.createNewFile();
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Saving fast/slow data to " + file.getAbsolutePath());
            }
            aepdataFileStream = new PrintWriter(file);
        } catch (java.io.FileNotFoundException e) {
            throw e;
        }

        char s = ' ';
        switch (dataSeparator) {
            case AttysEEG.DataRecorder.DATA_SEPARATOR_SPACE:
                s = ' ';
                break;
            case AttysEEG.DataRecorder.DATA_SEPARATOR_COMMA:
                s = ',';
                break;
            case AttysEEG.DataRecorder.DATA_SEPARATOR_TAB:
                s = 9;
                break;
        }

        for (int i = 0; i < fastSlowFullSeries.size(); i++) {
            aepdataFileStream.format("%e%c%e%c\n",
                    fastSlowFullSeries.getX(i).floatValue(), s,
                    fastSlowFullSeries.getY(i).floatValue(), s);
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
                                case AttysEEG.DataRecorder.DATA_SEPARATOR_COMMA:
                                    dataFilename = dataFilename + ".csv";
                                    break;
                                case AttysEEG.DataRecorder.DATA_SEPARATOR_SPACE:
                                    dataFilename = dataFilename + ".dat";
                                    break;
                                case AttysEEG.DataRecorder.DATA_SEPARATOR_TAB:
                                    dataFilename = dataFilename + ".tsv";
                            }
                        }
                        try {
                            writeSlowFastRatiofile();
                            Toast.makeText(getActivity(),
                                    "Successfully written '" + dataFilename + "' to the external memory",
                                    Toast.LENGTH_SHORT).show();
                        } catch (IOException e) {
                            Toast.makeText(getActivity(),
                                    "Write Error while saving '" + dataFilename + "' to the external memory",
                                    Toast.LENGTH_SHORT).show();
                            if (Log.isLoggable(TAG, Log.DEBUG)) {
                                Log.d(TAG, "Error saving file: ", e);
                            }
                        }
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
        if ((all != 0) && (band != 0)) {
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
            for (int i = 0; i < n; i++) {
                // add the latest history sample:
                fastSlowHistorySeries.addLast(step * delta_t, fastSlowRatio);
                step++;
            }

            // add the latest history sample:
            fastSlowHistorySeries.addLast(step * delta_t, fastSlowRatio);
            fastSlowFullSeries.addLast(step * delta_t, fastSlowRatio);
            step++;
            fastSlowPlot.redraw();
        }
    }
}
