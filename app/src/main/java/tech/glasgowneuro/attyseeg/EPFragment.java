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
    int SWEEP_DURATION_US_WITHOUT_CORRECTION = 500000;

    // this is our actual sweep duration derived
    // from the RTC and measuring the time between samples
    int sweep_duration_us;

    // powerline frequency
    float powerlineF = 50;

    // set the powerline frequency
    void setPowerlineF(float _powerlineF) {
        powerlineF = _powerlineF;
    }

    // the androidplot which holds the plot
    private XYPlot epPlot = null;

    // the actual graph
    private SimpleXYSeries epHistorySeries = null;

    // the checkerboards hosted by the main UI
    private StimulusView stimulusView1 = null;
    private StimulusView stimulusView2 = null;

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

    // actual total sweep duration
    long actual_sweep_duration_in_ns = 2000000;

    // sample index within a sweep
    int index = 0;

    // number of sweeps
    int nSweeps = 1;

    // ignore samples when it's false
    // used for startup
    boolean ready = false;

    // it's written on the tin
    boolean doSweeps = false;

    // when true data is added to the averaging array
    boolean acceptData = false;

    // filename
    private String dataFilename = null;

    // separator for the data file
    private byte dataSeparator = AttysComm.DATA_SEPARATOR_TAB;

    // used to calc the actual sampling interval
    long nanoTime = 0;
    long prev_nano_time = 0;
    long dt_avg;
    int ignoreCtr = 100;

    // overall highpass mainly to avoid eyeblinks and DC drift
    Butterworth highpass;

    // selects the stim mode
    private Spinner spinnerMode;

    // default mode is VEP
    int mode = MODE_VEP;

    // tracks what the spinner has for a mode
    int spinner_mode = mode;


    // stimulus generator for audio
    // uses the variables from the parent class to
    // start/stop the stim and timing
    class AudioStimulusGenerator implements Runnable {

        boolean doRun = true;

        // audio
        private AudioTrack sound;
        private byte[] rawAudio;
        int audioSamplingRate = 44100;
        int clickduration = audioSamplingRate / 1000; // 1ms
        int nAudioSamples = clickduration * 3;

        public void initSound() {
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

        @Override
        public void run() {

            initSound();

            while (doRun) {
                long t0 = System.nanoTime();

                while (doRun && doSweeps) {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                sound.pause();
                                sound.flush();
                                sound.reloadStaticData();
                                sound.play();
                            } catch (IllegalStateException e) {}
                        }
                    }).start();
                    while ((t0 - System.nanoTime()) > 0) {
                        yield();
                    }
                    t0 = t0 + actual_sweep_duration_in_ns;
                }
                while ((!doSweeps) && (doRun)) {
                    yield();
                }
            }
        }

        public void cancel() {
            doRun = false;
            sound.release();
        }
    }


    // stimulus generator for the checkerboard
    // uses the variables from the parent class to
    // start/stop the stim and timing
    class VisualStimulusGenerator implements Runnable {

        boolean doRun = true;
        boolean inverted = false;

        void setInvisible() {
            if (getActivity() != null) {
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (stimulusView1 != null) {
                            stimulusView1.setVisibility(View.INVISIBLE);
                        }
                        if (stimulusView2 !=null) {
                            stimulusView2.setVisibility(View.INVISIBLE);
                        }
                    }
                });
            }
        }

        @Override
        public void run() {

            inverted = false;

            // endless stimulation loop
            while (doRun) {
                // we record the absolute time and
                // then we add our stimulation interval and wait
                long t0 = System.nanoTime();

                // this loop runs as long as we stimuate and
                // falls through when we stop
                while (doRun && doSweeps) {
                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (inverted) {
                                stimulusView2.setVisibility(View.INVISIBLE);
                            } else {
                                stimulusView2.setVisibility(View.VISIBLE);
                            }
                            inverted = !inverted;
                        }
                    });
                    while ((t0 - System.nanoTime()) > 0) {
                        yield();
                    }
                    t0 = t0 + actual_sweep_duration_in_ns;
                }

                // falls through when stim is not on
                setInvisible();
                while ((!doSweeps) && (doRun)) {
                    yield();
                }

                // here we arrive when the stimulation starts
                getActivity().runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        stimulusView1.setVisibility(View.VISIBLE);
                    }
                });
            }
            setInvisible();
        }

        public void cancel() {
            doRun = false;
            setInvisible();
        }
    }

    AudioStimulusGenerator audioStimulusGenerator;
    VisualStimulusGenerator visualStimulusGenerator;

    // sets the sampling rate
    public void setSamplingrate(int _samplingrate) {
        samplingRate = _samplingrate;
        final long CONST1E9 = 1000000000;
        samplingInterval_ns = CONST1E9 / _samplingrate;
        dt_avg = samplingInterval_ns;
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

    // starts the sweeps
    public void startSweeps() {
        nSweeps = 1;
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
    }

    // called by the reset button and at startup
    private void reset() {
        ready = false;

        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                stimulusView1.setVisibility(View.INVISIBLE);
                stimulusView2.setVisibility(View.INVISIBLE);
            }
        });

        stopSweeps();

        if (audioStimulusGenerator != null) {
            audioStimulusGenerator.cancel();
        }
        if (visualStimulusGenerator != null) {
            visualStimulusGenerator.cancel();
        }

        audioStimulusGenerator = null;
        visualStimulusGenerator = null;

        switch (mode) {
            case MODE_VEP:
                visualStimulusGenerator = new VisualStimulusGenerator();
                new Thread(visualStimulusGenerator).start();
                break;
            case MODE_AEP:
                audioStimulusGenerator = new AudioStimulusGenerator();
                new Thread(audioStimulusGenerator).start();
                break;
        }

        highpass = new Butterworth();
        highpass.highPass(2, samplingRate, highpassFreq[mode]);
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

        nanoTime = System.nanoTime() + samplingInterval_ns;
        prev_nano_time = System.nanoTime();
        ignoreCtr = 100;

        epPlot.setTitle(string_ep_modes[mode]);
        epHistorySeries.setTitle(string_ep_modes[mode] + "/\u03bcV");
        epPlot.redraw();

        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                stimulusView1.setVisibility(View.INVISIBLE);
                stimulusView2.setVisibility(View.INVISIBLE);
            }
        });

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
            audioStimulusGenerator.cancel();
        }
        if (visualStimulusGenerator != null) {
            visualStimulusGenerator.cancel();
        }
    }

    // called when a sample arrives and is used to measure
    // the actual sampling rate and the actual sweep duration
    public void tick() {
        prev_nano_time = nanoTime;
        nanoTime = System.nanoTime();
        long dt_real = nanoTime - prev_nano_time;
        if (ignoreCtr > 0) {
            ignoreCtr--;
            return;
        }
        dt_avg = dt_avg + ((dt_real - dt_avg) / samplingRate / 50);
        actual_sweep_duration_in_ns = dt_avg * nSamples;
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

    // adds samples from the main UI usually in batches
    // so cannot be used for sampling rate estimation
    // but less comp demanding
    public synchronized void addValue(final float v) {

        if (!ready) return;

        if (!acceptData) return;

        if (epHistorySeries == null) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "epHistorySeries == null");
            }
            return;
        }

        double avg = epHistorySeries.getY(index).doubleValue();
        double v2 = highpass.filter(v * 1E6);
        double nSweepsD = (double) nSweeps;
        avg = ((nSweepsD - 1) / nSweepsD) * avg + (1 / nSweepsD) * v2;
        if (index < epHistorySeries.size()) {
            epHistorySeries.setY(avg, index);
        }
        index++;
        if (index == nSamples) {
            nSweeps++;
            index = 0;
            acceptData = doSweeps;
            if (doSweeps) {
                showSweeps();
            }
            epPlot.redraw();

        }
    }
}
