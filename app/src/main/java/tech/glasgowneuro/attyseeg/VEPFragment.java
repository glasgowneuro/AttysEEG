package tech.glasgowneuro.attyseeg;

import android.Manifest;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Paint;
import android.hardware.camera2.CameraManager;
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
 * Created by bp1 on 30/01/17.
 */

public class VEPFragment extends Fragment {

    String TAG = "VEPFragment";

    private SimpleXYSeries epHistorySeries = null;

    private XYPlot vepPlot = null;

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

    int flashTimer = 1;

    Timer timer = null;

    private String dataFilename = null;

    private byte dataSeparator = AttysComm.DATA_SEPARATOR_TAB;

    CameraManager cameraManager = null;
    String cameraId = null;

    public void setSamplingrate(int _samplingrate) {
        samplingRate = _samplingrate;
    }

    private void resetSoundTimer() {
        flashTimer = SWEEP_DURATION_MS / (1000 / samplingRate);
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
        //vepPlot.setRangeBoundaries(-10, 10, BoundaryMode.FIXED);
        vepPlot.setDomainBoundaries(0, tmax * 1000, BoundaryMode.FIXED);

        vepPlot.addSeries(epHistorySeries,
                new LineAndPointFormatter(
                        Color.rgb(100, 255, 255), null, null, null));
        vepPlot.setDomainLabel("t/msec");
        vepPlot.setRangeLabel("");

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
            vepPlot.setDomainStep(StepMode.INCREMENT_BY_VAL, 50);
        } else {
            vepPlot.setDomainStep(StepMode.INCREMENT_BY_VAL, 100);
        }

        initFlash();

        ready = true;
    }


    public void initFlash() {
        cameraManager = (CameraManager) getActivity().getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraId = cameraManager.getCameraIdList()[0];
        } catch (Exception e) {
            Log.d(TAG, "Could not find any flash");
        }
    }


    private class FlashTimer implements Runnable {

        @Override
        public synchronized void run() {
            try {
                cameraManager.setTorchMode(cameraId, true);
                Thread.sleep(100, 0);
                cameraManager.setTorchMode(cameraId, false);
            } catch (Exception e) {
                Log.d(TAG, "Could not switch on flash");
            }
        }
    }


    private void resetVEP() {
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

        view = inflater.inflate(R.layout.vepfragment, container, false);

        // setup the APR Levels plot:
        vepPlot = (XYPlot) view.findViewById(R.id.vepPlotView);
        sweepNoText = (TextView) view.findViewById(R.id.vep_nsweepsTextView);
        sweepNoText.setText(String.format("%04d sweeps", 0));
        toggleButtonDoSweep = (ToggleButton) view.findViewById(R.id.vep_doSweeps);
        toggleButtonDoSweep.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    acceptData = true;
                }
                doSweeps = isChecked;
            }
        });
        resetButton = (Button) view.findViewById(R.id.vepReset);
        resetButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                resetVEP();
            }
        });
        saveButton = (Button) view.findViewById(R.id.vepSave);
        saveButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                saveVEP();
            }
        });


        epHistorySeries = new SimpleXYSeries("VEP/uV");
        if (epHistorySeries == null) {
            if (Log.isLoggable(TAG, Log.ERROR)) {
                Log.e(TAG, "epHistorySeries == null");
            }
        }

        Paint paint = new Paint();
        paint.setColor(Color.argb(128, 0, 255, 0));
        vepPlot.getGraph().setDomainGridLinePaint(paint);
        vepPlot.getGraph().setRangeGridLinePaint(paint);

        reset();

        return view;

    }


    private void writeVEPfile() throws IOException {

        PrintWriter aepdataFileStream;

        if (dataFilename == null) return;

        File file;

        try {
            file = new File(AttysEEG.ATTYSDIR, dataFilename.trim());
            file.createNewFile();
            Log.d(TAG, "Saving VEP to " + file.getAbsolutePath());
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
                throw new IOException("VEP write error");
            }
        }

        aepdataFileStream.close();

        Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        Uri contentUri = Uri.fromFile(file);
        mediaScanIntent.setData(contentUri);
        getActivity().sendBroadcast(mediaScanIntent);
    }


    private void saveVEP() {

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
                .setTitle("Saving VEP data")
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
                            writeVEPfile();
                            Toast.makeText(getActivity(),
                                    "Successfully written '" + dataFilename + "' to the external memory",
                                    Toast.LENGTH_SHORT).show();
                        } catch (IOException e) {
                            Toast.makeText(getActivity(),
                                    "Write Error while saving '" + dataFilename + "' to the external memory",
                                    Toast.LENGTH_SHORT).show();
                            Log.d(TAG, "Error saving VEP file: ", e);
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
        flashTimer--;
        if (flashTimer == 0) {
//            Log.v(TAG,"Starting sweep at: "+samplenumber);
            if (doSweeps) {
                new Thread(new VEPFragment.FlashTimer()).start();
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
        if (vepPlot != null) {
            vepPlot.redraw();
        }
    }
}
