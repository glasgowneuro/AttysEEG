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
import android.util.SparseBooleanArray;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ListView;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.Timer;
import java.util.TimerTask;

import uk.me.berndporr.iirj.Butterworth;

import static tech.glasgowneuro.attyseeg.AttysEEG.ATTYSDIR;

/**
 * Evoked potentials Fragment
 */

public class EPFragment extends Fragment {

    static final String TAG = "EPFragment";

    static final int MODE_VEP = 0;
    static final int MODE_AEP = 1;

    public static final String[] string_ep_modes = {
            "VEP",
            "AEP"
    };

    final float[] highpassFreq = {
            1.0F, // VEP
            0.5F  // AEP
    };

    // this is our desired sweep duration
    private final static int[] SWEEP_DURATION_US_WITHOUT_CORRECTION = {
            600000, //VEP
            500000  //AEP
    };

    // with anti alias stuff
    private static int sweep_duration_us = SWEEP_DURATION_US_WITHOUT_CORRECTION[0];

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

    // the view which hosts all the views above
    View view = null;

    // number of samples calcuated from the desired sweep length
    int nSamples;

    // samplingrate of the Attys
    int samplingRate;

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
    private byte dataSeparator = AttysEEG.DataRecorder.DATA_SEPARATOR_TAB;

    // overall highpass mainly to avoid eyeblinks and DC drift
    static Butterworth highpass;

    // selects the stim mode
    private Spinner spinnerMode;

    // default mode is VEP
    int mode = MODE_VEP;

    // tracks what the spinner has for a mode
    int spinner_mode = mode;

    static private byte[] customAudioStimulus = null;

    // stimulus generator for audio
    // uses the variables from the parent class to
    // start/stop the stim and timing
    public static class AudioStimulusGenerator {

        // audio
        static private AudioTrack sound;
        private byte[] rawAudio;
        public static final int audioSamplingRate = 44100;
        public static final int clickduration = audioSamplingRate / 1000; // 1ms
        public static final int nAudioSamples = clickduration * 3;
        // let's assume the worse case: 20ms latency to trigger the sound
        public static final int maxNAudioSamples =
                (int) ((double) audioSamplingRate *
                        (SWEEP_DURATION_US_WITHOUT_CORRECTION[1]-20000) / 1.0E6);

        public AudioStimulusGenerator(byte[] _customStimulus) {
            if (_customStimulus == null) {
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
            } else {
                sound = new AudioTrack(AudioManager.STREAM_MUSIC,
                        audioSamplingRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_8BIT,
                        _customStimulus.length,
                        AudioTrack.MODE_STATIC);
                if (sound == null) return;
                sound.write(_customStimulus, 0, _customStimulus.length);
//                for(byte sample : _customStimulus) {
//                    Log.d(TAG," "+(sample & 0xff));
//                }
            }
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
            if ((stimulusView1 != null) && (stimulusView2 != null) && (getActivity() != null)) {
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

        if (timer != null) {
            timer.cancel();
        }

        stopSweeps();

        if (visualStimulusGenerator != null) {
            visualStimulusGenerator.setInvisible();
        }

        audioStimulusGenerator = null;
        visualStimulusGenerator = null;

        sweep_duration_us = SWEEP_DURATION_US_WITHOUT_CORRECTION[mode] +
                (int) (1000000.0 / powerlineF / 2.0);

        switch (mode) {
            case MODE_VEP:
                visualStimulusGenerator = new VisualStimulusGenerator();
                break;
            case MODE_AEP:
                audioStimulusGenerator = new AudioStimulusGenerator(customAudioStimulus);
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

        Screensize screensize = new Screensize(getActivity().getWindowManager());
        if (screensize.isTablet()) {
            epPlot.setDomainStep(StepMode.INCREMENT_BY_VAL, 50);
        } else {
            epPlot.setDomainStep(StepMode.INCREMENT_BY_VAL, 100);
        }

        epPlot.setTitle(string_ep_modes[mode]);
        epHistorySeries.setTitle(string_ep_modes[mode] + "/\u03bcV");
        epPlot.redraw();

        setStimInvisible();

        timer = new Timer();
        startSweepTask = new StartSweepTask();
        timer.schedule(startSweepTask, sweep_duration_us / 1000, sweep_duration_us / 1000);

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

        view = inflater.inflate(R.layout.epfragment, container, false);

        setHasOptionsMenu(true);

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


    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.epoptions, menu);
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        switch (item.getItemId()) {
            case R.id.epfragmentsave:
                saveEP();
                return true;

            case R.id.epfragmentloadwav:
                cumstomAudioDialogue();
                return true;

            case R.id.epdefaultstim:
                customAudioStimulus = null;
                return true;

            default:
                // If we got here, the user's action was not recognized.
                // Invoke the superclass to handle it.
                return super.onOptionsItemSelected(item);

        }
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
            file = new File(ATTYSDIR, dataFilename.trim());
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


    private void loadAudio(final String filename) {
        File fp = new File(ATTYSDIR, filename);
        try {
            Scanner scanner = new Scanner(fp);
            int n = 0;
            customAudioStimulus = new byte[AudioStimulusGenerator.maxNAudioSamples];
            while ((scanner.hasNext()) && (n < AudioStimulusGenerator.maxNAudioSamples)) {
                int v = (int) (127.0 * Float.parseFloat(scanner.next()));
                v = v + 0x80;
                customAudioStimulus[n] = (byte) (v & 0xff);
                //Log.d(TAG, "n=" + n + " " + (customAudioStimulus[n] & 0xff));
                n++;
            }
            while (n < AudioStimulusGenerator.maxNAudioSamples) {
                customAudioStimulus[n] = (byte) 0x80;
                n++;
            }
            getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    Toast.makeText(getActivity(),
                            "Successfully loaded '" + filename + "'",
                            Toast.LENGTH_SHORT).show();
                }
            });
        } catch (final Exception e) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, filename + " loading error", e);
            }
            getActivity().runOnUiThread(new Runnable() {
                public void run() {
                    Toast.makeText(getActivity(),
                            "Error loading '" + filename + "': " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                }
            });
        }
    }


    private void cumstomAudioDialogue() {

        final int REQUEST_EXTERNAL_STORAGE = 1;
        String[] PERMISSIONS_STORAGE = {
                Manifest.permission.READ_EXTERNAL_STORAGE
        };

        int permission = ActivityCompat.checkSelfPermission(getContext(), Manifest.permission.READ_EXTERNAL_STORAGE);

        if (permission != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    getActivity(),
                    PERMISSIONS_STORAGE,
                    REQUEST_EXTERNAL_STORAGE
            );
        }

        final List files = new ArrayList();
        final String[] list = ATTYSDIR.list();
        for (String file : list) {
            if (files != null) {
                if (file != null) {
                    files.add(file);
                }
            }
        }

        final ListView listview = new ListView(getContext());
        ArrayAdapter adapter = new ArrayAdapter(getContext(),
                android.R.layout.simple_list_item_multiple_choice,
                files);
        listview.setAdapter(adapter);
        listview.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        listview.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                view.setSelected(true);
            }
        });

        new AlertDialog.Builder(getContext())
                .setTitle("Loading custom stimulus for AEP")
                .setMessage("Select a filename. " +
                        "It needs to have one sample per row and less or qual than n=" +
                        AudioStimulusGenerator.maxNAudioSamples +
                        " samples ranging between -0..+1 at a sampling rate of fs=" +
                        AudioStimulusGenerator.audioSamplingRate + ".")
                .setView(listview)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        SparseBooleanArray checked = listview.getCheckedItemPositions();
                        for (int i = 0; i < listview.getCount(); i++) {
                            if (checked.get(i)) {
                                final String filename = list[i];
                                new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        loadAudio(filename);
                                    }
                                }).start();
                                if (Log.isLoggable(TAG, Log.DEBUG)) {
                                    Log.d(TAG, "filename=" + filename);
                                }
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
