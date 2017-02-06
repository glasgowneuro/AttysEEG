package tech.glasgowneuro.attyseeg;

import android.Manifest;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
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
import uk.me.berndporr.iirj.Butterworth;

import static java.lang.Thread.yield;

/**
 * Evoked potentials Fragment
 */

public class EPFragment extends Fragment {

    String TAG = "EPFragment";

    static final int MODE_VEP = 0;
    static final int MODE_AEP = 1;

    public static final String[] string_ep_modes = {
            "VEP",
            "AEP"
    };

    final float[] highpassFreq = {
            0.5F, // VEP
            0.5F  // AEP
    };

    // this is our desired sweep duration
    private final static int SWEEP_DURATION_US_WITHOUT_CORRECTION = 500000;

    // with anti alias stuff
    private static int sweep_duration_us;

    Timer timer = null;

    private static StartSweepTask startSweepTask = null;

    // powerline frequency
    private float powerlineF = 50;

    private final int mainsNotchOrder = 2;
    private final float mainsNotchBW = 5;

    static private Butterworth notch_mains_fundamental = null;
    static private Butterworth notch_mains_1st_harmonic = null;

    // set the powerline frequency
    void setPowerlineF(float _powerlineF) {
        powerlineF = _powerlineF;
    }

    // the androidplot which holds the plot
    private XYPlot epPlot = null;

    // the actual graph
    private SimpleXYSeries epHistorySeries = null;

    // the checkerboards hosted by the main UI
    static private StimulusView stimulusView1 = null;
    static private StimulusView stimulusView2 = null;

    // keeps the sweep numbers
    private TextView sweepNoText = null;

    // sweeps on/off
    private ToggleButton toggleButtonDoSweep;

    // reset button to get rid of the sweeps
    private Button resetButton;

    // saving the AEP data
    private Button saveButton;

    // the view which hosts all the views above
    View view = null;

    // number of samples calcuated from the desired sweep length
    int nSamples;

    // samplingrate of the Attys
    int samplingRate;

    // actual sampling interval in ns for the
    // stimulus generator
    long samplingInterval_ns = 1;

    // sample index within a sweep
    static int index = 0;

    // number of sweeps
    static int nSweeps = 1;

    // ignore samples when it's false
    // used for startup
    static boolean ready = false;

    // it's written on the tin
    static boolean doSweeps = false;

    // when true data is added to the averaging array
    static boolean acceptData = false;

    // filename
    private String dataFilename = null;

    // separator for the data file
    private byte dataSeparator = AttysComm.DATA_SEPARATOR_TAB;

    // overall highpass mainly to avoid eyeblinks and DC drift
    static Butterworth highpass;

    // selects the stim mode
    private Spinner spinnerMode;

    // default mode is VEP
    int mode = MODE_VEP;

    // tracks what the spinner has for a mode
    int spinner_mode = mode;


    // stimulus generator for audio
    // uses the variables from the parent class to
    // start/stop the stim and timing
    static class AudioStimulusGenerator {

        // audio
        static private AudioTrack sound;
        private byte[] rawAudio;
        int audioSamplingRate = 44100;
        int clickduration = audioSamplingRate / 1000; // 1ms
        int nAudioSamples = clickduration * 3;

        public AudioStimulusGenerator() {
            sound = new AudioTrack(AudioManager.STREAM_MUSIC,
                    audioSamplingRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_8BIT,
                    nAudioSamples,
                    AudioTrack.MODE_STATIC);
            if (sound == null) return;
            rawAudio = new byte[nAudioSamples];
            for (int i = 0; i < nAudioSamples; i++) {
                rawAudio[i] = (byte) 0x80;
            }
            for (int i = 0; i < clickduration; i++) {
                rawAudio[i] = (byte) 0x00;
                rawAudio[i + clickduration] = (byte) 0xff;
            }
            sound.write(rawAudio, 0, rawAudio.length);
        }

        static synchronized public void stim() {
            try {
                sound.pause();
                sound.flush();
                sound.reloadStaticData();
                sound.play();
            } catch (IllegalStateException e) {
            }
        }

        public void closeAudio() {
            sound.release();
        }
    }


    // stimulus generator for the checkerboard
    // uses the variables from the parent class to
    // start/stop the stim and timing
    class VisualStimulusGenerator {

        boolean inverted = false;

        public void setInvisible() {
            if (getActivity() != null) {
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (stimulusView1 != null) {
                            stimulusView1.setVisibility(View.INVISIBLE);
                        }
                        if (stimulusView2 != null) {
                            stimulusView2.setVisibility(View.INVISIBLE);
                        }
                    }
                });
            }
        }

        public void stim() {
            if ((stimulusView1 != null) && (stimulusView2 != null) && (getActivity() != null)){
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (doSweeps) {
                            if (inverted) {
                                stimulusView2.setVisibility(View.INVISIBLE);
                            } else {
                                stimulusView2.setVisibility(View.VISIBLE);
                            }
                            stimulusView1.setVisibility(View.VISIBLE);
                            inverted = !inverted;
                        } else {
                            if (stimulusView1 != null) {
                                stimulusView1.setVisibility(View.INVISIBLE);
                            }
                            if (stimulusView2 != null) {
                                stimulusView2.setVisibility(View.INVISIBLE);
                            }
                        }
                    }
                });
            }
        }
    }

    AudioStimulusGenerator audioStimulusGenerator;
    VisualStimulusGenerator visualStimulusGenerator;

    // sets the sampling rate
    public void setSamplingrate(int _samplingrate) {
        samplingRate = _samplingrate;
        final long CONST1E9 = 1000000000;
        samplingInterval_ns = CONST1E9 / _samplingrate;
    }

    // sets the background layer of the visual stimulus
    public void setStimulusView1(StimulusView _stimulusView) {
        stimulusView1 = _stimulusView;
        stimulusView1.setInverted(false);
    }

    // sets the foreground layer of the visual stimulus
    public void setStimulusView2(StimulusView _stimulusView) {
        stimulusView2 = _stimulusView;
        stimulusView2.setInverted(true);
    }

    private class StartSweepTask extends TimerTask {

        public synchronized void run() {
            epPlot.redraw();
            if (!doSweeps) {
                acceptData = false;
                return;
            }
            nSweeps++;
            showSweeps();
            index = 0;
            if (audioStimulusGenerator != null) {
                audioStimulusGenerator.stim();
            }
            if (visualStimulusGenerator != null) {
                visualStimulusGenerator.stim();
            }
        }
    }

    // starts the sweeps
    public void startSweeps() {
        showSweeps();
        index = 0;
        acceptData = true;
        doSweeps = true;
    }

    // stops them
    public void stopSweeps() {
        if (toggleButtonDoSweep != null) {
            toggleButtonDoSweep.setChecked(false);
        }
        doSweeps = false;
        setStimInvisible();
    }

    private void setStimInvisible() {
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (stimulusView1 != null) {
                    stimulusView1.setVisibility(View.INVISIBLE);
                }
                if (stimulusView2 != null) {
                    stimulusView2.setVisibility(View.INVISIBLE);
                }
            }
        });
    }

    // called by the reset button and at startup
    private void reset() {
        ready = false;

        stopSweeps();

        if (visualStimulusGenerator != null) {
            visualStimulusGenerator.setInvisible();
        }

        audioStimulusGenerator = null;
        visualStimulusGenerator = null;

        switch (mode) {
            case MODE_VEP:
                visualStimulusGenerator = new VisualStimulusGenerator();
                break;
            case MODE_AEP:
                audioStimulusGenerator = new AudioStimulusGenerator();
                break;
        }

        highpass = new Butterworth();
        highpass.highPass(2, samplingRate, highpassFreq[mode]);

        notch_mains_fundamental = new Butterworth();
        notch_mains_fundamental.bandStop(mainsNotchOrder, samplingRate, powerlineF, mainsNotchBW);
        notch_mains_1st_harmonic = new Butterworth();
        notch_mains_1st_harmonic.bandStop(mainsNotchOrder, samplingRate, powerlineF * 2, mainsNotchBW);

        nSamples = samplingRate * sweep_duration_us / 1000000;
        float tmax = nSamples * (1.0F / ((float) samplingRate));
        epPlot.setDomainBoundaries(0, tmax * 1000, BoundaryMode.FIXED);

        int n = epHistorySeries.size();
        for (int i = 0; i < n; i++) {
            epHistorySeries.removeLast();
        }

        for (int i = 0; i < nSamples; i++) {
            epHistorySeries.addLast(1000.0F * (float) i * (1.0F / ((float) samplingRate)), 0.0);
        }

        index = 0;
        nSweeps = 0;
        showSweeps();
        nSweeps++;

        DisplayMetrics metrics = new DisplayMetrics();
        getActivity().getWindowManager().getDefaultDisplay().getMetrics(metrics);
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        if ((height > 1000) && (width > 1000)) {
            epPlot.setDomainStep(StepMode.INCREMENT_BY_VAL, 50);
        } else {
            epPlot.setDomainStep(StepMode.INCREMENT_BY_VAL, 100);
        }

        epPlot.setTitle(string_ep_modes[mode]);
        epHistorySeries.setTitle(string_ep_modes[mode] + "/\u03bcV");
        epPlot.redraw();

        setStimInvisible();

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

        sweep_duration_us = SWEEP_DURATION_US_WITHOUT_CORRECTION + (int) (1000000.0 / powerlineF / 2.0);

        view = inflater.inflate(R.layout.epfragment, container, false);

        // setup the APR Levels plot:
        epPlot = (XYPlot) view.findViewById(R.id.bpmPlotView);
        sweepNoText = (TextView) view.findViewById(R.id.nsweepsTextView);
        sweepNoText.setText(" ");
        toggleButtonDoSweep = (ToggleButton) view.findViewById(R.id.doSweeps);
        toggleButtonDoSweep.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    startSweeps();
                } else {
                    stopSweeps();
                }
            }
        });
        resetButton = (Button) view.findViewById(R.id.aepReset);
        resetButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mode = spinner_mode;
                reset();
            }
        });
        saveButton = (Button) view.findViewById(R.id.aepSave);
        saveButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                saveEP();
            }
        });

        spinnerMode = (Spinner) view.findViewById(R.id.ep_mode);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(),
                android.R.layout.simple_spinner_dropdown_item,
                string_ep_modes);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerMode.setAdapter(adapter);
        spinnerMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (mode != position) {
                    Toast.makeText(getActivity(),
                            "Press RESET to confirm to switch to " + string_ep_modes[position],
                            Toast.LENGTH_SHORT).show();
                }
                spinner_mode = position;
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        spinnerMode.setBackgroundResource(android.R.drawable.btn_default);

        epHistorySeries = new SimpleXYSeries(" ");
        if (epHistorySeries == null) {
            if (Log.isLoggable(TAG, Log.ERROR)) {
                Log.e(TAG, "epHistorySeries == null");
            }
        }

        epPlot.addSeries(epHistorySeries,
                new LineAndPointFormatter(
                        Color.rgb(100, 255, 255), null, null, null));
        epPlot.setDomainLabel("t/msec");
        epPlot.setRangeLabel("");

        Paint paint = new Paint();
        paint.setColor(Color.argb(128, 0, 255, 0));
        epPlot.getGraph().setDomainGridLinePaint(paint);
        epPlot.getGraph().setRangeGridLinePaint(paint);

        reset();

        timer = new Timer();
        startSweepTask = new StartSweepTask();
        timer.schedule(startSweepTask, sweep_duration_us / 1000, sweep_duration_us / 1000);

        return view;
    }

    @Override
    public void onStop() {
        super.onStop();
        stopSweeps();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (audioStimulusGenerator != null) {
            audioStimulusGenerator.closeAudio();
        }
        if (visualStimulusGenerator != null) {
            visualStimulusGenerator.setInvisible();
        }

        timer.cancel();
    }

    private void writeEPfile() throws IOException {

        PrintWriter aepdataFileStream;

        if (dataFilename == null) return;

        File file;

        try {
            file = new File(AttysEEG.ATTYSDIR, dataFilename.trim());
            file.createNewFile();
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Saving AEP to " + file.getAbsolutePath());
            }
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

        for (int i = 0; i < nSamples; i++) {
            aepdataFileStream.format("%e%c%e%c\n",
                    epHistorySeries.getX(i).floatValue(), s,
                    epHistorySeries.getY(i).floatValue(), s);
            if (aepdataFileStream.checkError()) {
                throw new IOException("AEP write error");
            }
        }

        aepdataFileStream.close();

        Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        Uri contentUri = Uri.fromFile(file);
        mediaScanIntent.setData(contentUri);
        getActivity().sendBroadcast(mediaScanIntent);
    }


    private void saveEP() {

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
                .setTitle("Saving AEP data")
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
                            writeEPfile();
                            Toast.makeText(getActivity(),
                                    "Successfully written '" + dataFilename + "' to the external memory",
                                    Toast.LENGTH_SHORT).show();
                        } catch (IOException e) {
                            Toast.makeText(getActivity(),
                                    "Write Error while saving '" + dataFilename + "' to the external memory",
                                    Toast.LENGTH_SHORT).show();
                            if (Log.isLoggable(TAG, Log.DEBUG)) {
                                Log.d(TAG, "Error saving AEP file: ", e);
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


    public void showSweeps() {
        if (getActivity() != null) {
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (sweepNoText != null) {
                        sweepNoText.setText(String.format("%04d sweeps", nSweeps));
                    }
                }
            });
        }
    }

    // adds samples
    public void addValue(float v) {

        if (!ready) return;

        double v2 = highpass.filter(v * 1E6);
        v2 = notch_mains_fundamental.filter(v2);
        v2 = notch_mains_1st_harmonic.filter(v2);

        if (!acceptData) return;

        if (epHistorySeries == null) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "epHistorySeries == null");
            }
            return;
        }

        if (index < epHistorySeries.size()) {
            double avg = epHistorySeries.getY(index).doubleValue();
            double nSweepsD = (double) nSweeps;
            avg = ((nSweepsD - 1) / nSweepsD) * avg + (1 / nSweepsD) * v2;
            //avg = v2;
            epHistorySeries.setY(avg, index);
            index++;
        }
    }
}
