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

import tech.glasgowneuro.attyscomm.AttysComm;

/**
 * Created by Bernd Porr on 20/01/17.
 * <p>
 * Heartrate Plot
 */

public class AEPFragment extends Fragment {

    String TAG = "AEPFragment";

    private SimpleXYSeries epHistorySeries = null;

    private XYPlot aepPlot = null;

    private TextView sweepNoText = null;

    private ToggleButton toggleButtonDoSweep;

    private Button resetButton;

    private Button saveButton;

    View view = null;

    // in secs
    final int SWEEP_DURATION_MS = 501;

    int nSamples = 250 / 2;

    int samplingRate = 250;

    int index = 0;

    int nSweeps = 1;

    boolean ready = false;

    boolean doSweeps = false;

    boolean acceptData = false;

    int soundTimer = 1;

    Timer timer = null;

    private String dataFilename = null;

    private byte dataSeparator = AttysComm.DATA_SEPARATOR_TAB;

    public void setSamplingrate(int _samplingrate) {
        samplingRate = _samplingrate;
    }

    private void resetSoundTimer() {
        soundTimer = SWEEP_DURATION_MS / (1000 / samplingRate);
    }

    public void stopSweeps() {
        if (timer != null) {
            timer.cancel();
        }
        if (toggleButtonDoSweep != null) {
            toggleButtonDoSweep.setChecked(false);
        }
        timer = null;
        doSweeps = false;
        acceptData = false;
    }

    private void reset() {
        ready = false;
        nSamples = (int) (samplingRate * SWEEP_DURATION_MS / 1000);
        resetSoundTimer();
        float tmax = nSamples * (1.0F / ((float) samplingRate));
        //aepPlot.setRangeBoundaries(-10, 10, BoundaryMode.FIXED);
        aepPlot.setDomainBoundaries(0, tmax * 1000, BoundaryMode.FIXED);

        aepPlot.addSeries(epHistorySeries,
                new LineAndPointFormatter(
                        Color.rgb(100, 255, 255), null, null, null));
        aepPlot.setDomainLabel("t/msec");
        aepPlot.setRangeLabel("");

        for (int i = 0; i < nSamples; i++) {
            epHistorySeries.addLast(1000.0F * (float) i * (1.0F / ((float) samplingRate)), 0.0);
        }

        index = 0;
        nSweeps = 1;

        DisplayMetrics metrics = new DisplayMetrics();
        getActivity().getWindowManager().getDefaultDisplay().getMetrics(metrics);
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        if ((height > 1000) && (width > 1000)) {
            aepPlot.setDomainStep(StepMode.INCREMENT_BY_VAL, 50);
        } else {
            aepPlot.setDomainStep(StepMode.INCREMENT_BY_VAL, 100);
        }

        initSound();

        ready = true;
    }


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


    private class ClickSoundTimer implements Runnable {

        @Override
        public synchronized void run() {
            switch (sound.getPlayState()) {
                case AudioTrack.PLAYSTATE_PAUSED:
                    sound.stop();
                    sound.reloadStaticData();
                    sound.play();
                    break;
                case AudioTrack.PLAYSTATE_PLAYING:
                    sound.stop();
                    sound.reloadStaticData();
                    sound.play();
                    break;
                case AudioTrack.PLAYSTATE_STOPPED:
                    sound.reloadStaticData();
                    sound.play();
                    break;
                default:
                    break;
            }
        }
    }


    private void resetAEP() {
        for (int i = 0; i < nSamples; i++) {
            epHistorySeries.setY(0, i);
        }
        nSweeps = 1;
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
        aepPlot = (XYPlot) view.findViewById(R.id.bpmPlotView);
        sweepNoText = (TextView) view.findViewById(R.id.nsweepsTextView);
        sweepNoText.setText(String.format("%04d sweeps", 0));
        toggleButtonDoSweep = (ToggleButton) view.findViewById(R.id.doSweeps);
        toggleButtonDoSweep.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    acceptData = true;
                }
                doSweeps = isChecked;
            }
        });
        resetButton = (Button) view.findViewById(R.id.aepReset);
        resetButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                resetAEP();
            }
        });
        saveButton = (Button) view.findViewById(R.id.aepSave);
        saveButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                saveAEP();
            }
        });


        epHistorySeries = new SimpleXYSeries("AEP/uV");
        if (epHistorySeries == null) {
            if (Log.isLoggable(TAG, Log.ERROR)) {
                Log.e(TAG, "epHistorySeries == null");
            }
        }

        Paint paint = new Paint();
        paint.setColor(Color.argb(128, 0, 255, 0));
        aepPlot.getGraph().setDomainGridLinePaint(paint);
        aepPlot.getGraph().setRangeGridLinePaint(paint);

        reset();

        return view;

    }


    private void writeAEPfile() throws IOException {

        PrintWriter aepdataFileStream;

        if (dataFilename == null) return;

        File file;

        try {
            file = new File(AttysEEG.ATTYSDIR, dataFilename.trim());
            file.createNewFile();
            Log.d(TAG,"Saving AEP to "+file.getAbsolutePath());
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
                    epHistorySeries.getX(i), s,
                    epHistorySeries.getY(i), s);
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


    private void saveAEP() {

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
                            writeAEPfile();
                            Toast.makeText(getActivity(),
                                    "Successfully written '" + dataFilename + "' to the external memory",
                                    Toast.LENGTH_SHORT).show();
                        } catch (IOException e) {
                            Toast.makeText(getActivity(),
                                    "Write Error while saving '" + dataFilename + "' to the external memory",
                                    Toast.LENGTH_SHORT).show();
                            Log.d(TAG, "Error saving AEP file: ", e);
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


    public void tick(long samplenumber) {
        if (!ready) return;
        if (!acceptData) return;
        soundTimer--;
        if (soundTimer == 0) {
//            Log.v(TAG,"Starting sweep at: "+samplenumber);
            if (doSweeps) {
                new Thread(new ClickSoundTimer()).start();
                nSweeps++;
            } else {
                acceptData = false;
            }
            resetSoundTimer();
            index = 0;
        }
    }

    public synchronized void addValue(final float v) {

        if (!ready) return;

        if (!acceptData) return;

        if (index >= nSamples) return;

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

        if (epHistorySeries == null) {
            if (Log.isLoggable(TAG, Log.VERBOSE)) {
                Log.v(TAG, "epHistorySeries == null");
            }
            return;
        }

        double avg = epHistorySeries.getY(index).doubleValue();
        double v2 = v * 1E6;
        //avg = (avg + new_value)/2;
        double nSweepsD = (double) nSweeps;
        avg = ((nSweepsD - 1) / nSweepsD) * avg + (1 / nSweepsD) * v2;
        // Log.d(TAG,"avg="+avg);
        if (index < epHistorySeries.size()) {
            epHistorySeries.setY(avg, index);
        }
        index++;
    }

    public void redraw() {
        if (aepPlot != null) {
            aepPlot.redraw();
        }
    }
}
