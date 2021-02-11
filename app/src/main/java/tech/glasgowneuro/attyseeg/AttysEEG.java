package tech.glasgowneuro.attyseeg;

import android.graphics.Color;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.widget.ProgressBar;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;

import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Toast;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

import tech.glasgowneuro.attyscomm.AttysComm;
import uk.me.berndporr.iirj.Butterworth;

public class AttysEEG extends AppCompatActivity {

    static private final int REFRESH_IN_MS = 50;

    private final float DEFAULT_GAIN = 4000;

    // overall filtering
    private static final float allChLowpassF = 200;
    private static final float allChHighpassF = 0.5F;

    // EEG frequency bands
    private static final float betaFlow = 13;
    private static final float betaFhigh = 30;
    private static final float alphaFlow = 8;
    private static final float alphaFhigh = 13;
    private static final float thetaFlow = 4;
    private static final float thetaFhigh = 8;
    private static final float deltaFhigh = 4;
    private static final float gammaFlow = 30;

    public static final double notchBW = 2.5; // Hz
    public static final int notchOrder = 2;

    // Fragments
    // add yours here !
    private EPFragment epFragment = null;
    private FastSlowRatioFragment betaRatioFragment = null;
    private BarGraphFragment barGraphFragment = null;


    /////////////////////////////////////////////////////
    // private stuff from here
    private boolean showBeta = true;
    private boolean showAlpha = true;
    private boolean showTheta = true;
    private boolean showDelta = true;
    private boolean showGamma = true;

    private RealtimePlotView realtimePlotView = null;
    private InfoView infoView = null;
    private StimulusView stimulusView1 = null;
    private StimulusView stimulusView2 = null;

    private BluetoothAdapter BA;
    private AttysComm attysComm = null;
    private byte samplingRate = AttysComm.ADC_RATE_500Hz;

    private Timer timer = null;

    UpdatePlotTask updatePlotTask = null;

    MenuItem menuItemPref = null;
    MenuItem menuItemEnterFilename = null;
    MenuItem menuItemRec = null;
    MenuItem menuItemBrowser = null;
    MenuItem menuItemSource = null;

    private static final String TAG = "AttysEEG";

    private Butterworth highpass = null;
    private Butterworth notch_mains_fundamental = null;
    private Butterworth notch_mains_1st_harmonic = null;
    private Butterworth notch_mains_2nd_harmonic = null;
    private Butterworth lowpass = null;
    private Butterworth betaHighpass = null;
    private Butterworth betaLowpass = null;
    private Butterworth deltaLowpass = null;
    private Butterworth alphaHighpass = null;
    private Butterworth alphaLowpass = null;
    private Butterworth thetaHighpass = null;
    private Butterworth thetaLowpass = null;
    private Butterworth gammaHighpass = null;
    private float powerlineHz = 50;

    private float gain = DEFAULT_GAIN;

    private float ytick = 0;

    int ygapForInfo = 0;

    private int timestamp = 0;

    private String dataFilename = null;
    private final byte dataSeparator = 0;

    ProgressBar progress = null;

    AlertDialog alertDialog = null;

    public class DataRecorder {
        /////////////////////////////////////////////////////////////
        // saving data into a file

        public final static byte DATA_SEPARATOR_TAB = 0;
        public final static byte DATA_SEPARATOR_COMMA = 1;
        public final static byte DATA_SEPARATOR_SPACE = 2;

        private PrintWriter textdataFileStream = null;
        private File textdataFile = null;
        private byte data_separator = DATA_SEPARATOR_TAB;
        long sample = 0;
        File file = null;

        // starts the recording
        public void startRec(File _file) throws java.io.FileNotFoundException {
            file = _file;
            sample = 0;
            textdataFileStream = new PrintWriter(file);
            textdataFile = file;
            messageListener.haveMessage(AttysComm.MESSAGE_STARTED_RECORDING);
        }

        // stops it
        public void stopRec() {
            if (textdataFileStream != null) {
                textdataFileStream.close();
                if (messageListener != null) {
                    messageListener.haveMessage(AttysComm.MESSAGE_STOPPED_RECORDING);
                }
                textdataFileStream = null;
                textdataFile = null;
                if (file != null) {
                    Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                    Uri contentUri = Uri.fromFile(file);
                    mediaScanIntent.setData(contentUri);
                    sendBroadcast(mediaScanIntent);
                }
            }
        }

        // are we recording?
        public boolean isRecording() {
            return (textdataFileStream != null);
        }

        public File getFile() {
            return textdataFile;
        }

        public void setDataSeparator(byte s) {
            data_separator = s;
        }

        public byte getDataSeparator() {
            return data_separator;
        }

        private void saveData(float rawEEG,
                              float filteredEEG,
                              float beta,
                              float alpha,
                              float theta,
                              float delta,
                              float gamma) {


            if (textdataFileStream == null) return;

            char s = ' ';
            switch (data_separator) {
                case DataRecorder.DATA_SEPARATOR_SPACE:
                    s = ' ';
                    break;
                case DataRecorder.DATA_SEPARATOR_COMMA:
                    s = ',';
                    break;
                case DataRecorder.DATA_SEPARATOR_TAB:
                    s = 9;
                    break;
            }
            double t = (double)sample / attysComm.getSamplingRateInHz();

            String tmp = String.format(Locale.US,"%e%c", t, s);
            tmp = tmp + String.format(Locale.US,"%e%c", rawEEG, s);
            tmp = tmp + String.format(Locale.US,"%e%c", filteredEEG, s);
            tmp = tmp + String.format(Locale.US,"%e%c", delta, s);
            tmp = tmp + String.format(Locale.US,"%e%c", theta, s);
            tmp = tmp + String.format(Locale.US,"%e%c", alpha, s);
            tmp = tmp + String.format(Locale.US,"%e%c", beta, s);
            tmp = tmp + String.format(Locale.US,"%e%c", gamma, s);
            sample++;
            if (textdataFileStream != null) {
                textdataFileStream.format("%s\n", tmp);
                if (textdataFileStream != null) {
                    if (textdataFileStream.checkError()) {
                        if (Log.isLoggable(TAG, Log.ERROR)) {
                            Log.e(TAG, "Error while saving");
                        }
                    }
                }
            }
        }
    }


    AttysComm.DataListener dataListener = new AttysComm.DataListener() {
        @Override
        public void gotData(long l, float[] f) {
            if (epFragment != null) {
                epFragment.addValue(f[AttysComm.INDEX_Analogue_channel_1]);
            }
        }
    };


    DataRecorder dataRecorder = new DataRecorder();

    AttysComm.MessageListener messageListener = new AttysComm.MessageListener() {
        @Override
        public void haveMessage(final int msg) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    switch (msg) {
                        case AttysComm.MESSAGE_ERROR:
                            Toast.makeText(getApplicationContext(),
                                    "Bluetooth connection problem", Toast.LENGTH_SHORT).show();
                            if (attysComm != null) {
                                attysComm.stop();
                            }
                            progress.setVisibility(View.GONE);
                            finish();
                            break;
                        case AttysComm.MESSAGE_CONNECTED:
                            progress.setVisibility(View.GONE);
                            break;
                        case AttysComm.MESSAGE_RETRY:
                            progress.setVisibility(View.VISIBLE);
                            Toast.makeText(getApplicationContext(),
                                    "Bluetooth - trying to connect. Please be patient.",
                                    Toast.LENGTH_SHORT).show();
                            break;
                        case AttysComm.MESSAGE_STARTED_RECORDING:
                            Toast.makeText(getApplicationContext(),
                                    "Started recording data to external storage.",
                                    Toast.LENGTH_SHORT).show();
                            break;
                        case AttysComm.MESSAGE_STOPPED_RECORDING:
                            Toast.makeText(getApplicationContext(),
                                    "Finished recording data to external storage.",
                                    Toast.LENGTH_SHORT).show();
                            break;
                        case AttysComm.MESSAGE_CONNECTING:
                            progress.setVisibility(View.VISIBLE);
                    }
                }
            });
        }
    };


    private class UpdatePlotTask extends TimerTask {

        private void annotatePlot() {
            String small = "";
            small = small + String.format(Locale.US,"1 sec/div, %1.02f mV/div", ytick * 1000);
            if (dataRecorder.isRecording()) {
                small = small + " !!RECORDING to:" + dataFilename;
            }
            if (infoView != null) {
                if (attysComm != null) {
                    infoView.drawText(small);
                }
            }
        }

        public synchronized void run() {

            if (attysComm != null) {
                if (attysComm.hasFatalError()) {
                    return;
                }
            }
            if (attysComm != null) {
                if (!attysComm.hasActiveConnection()) return;
            }

            int nCh = 0;
            if (attysComm != null) nCh = AttysComm.NCHANNELS;
            if (attysComm != null) {
                float[] tmpSample = new float[nCh];
                float[] tmpMin = new float[nCh];
                float[] tmpMax = new float[nCh];
                float[] tmpTick = new float[nCh];
                String[] tmpLabels = new String[nCh];

                float max = attysComm.getADCFullScaleRange(0) / gain;
                ytick = 50E-6F;
                annotatePlot();

                int n = 0;
                if (attysComm != null) {
                    n = attysComm.getNumSamplesAvilable();
                }
                if (realtimePlotView != null) {
                    if (!realtimePlotView.startAddSamples(n)) return;
                    for (int i = 0; ((i < n) && (attysComm != null)); i++) {
                        float[] sample = null;
                        if (attysComm != null) {
                            sample = attysComm.getSampleFromBuffer();
                        }
                        if (sample != null) {
                            // debug ECG detector
                            // sample[AttysComm.INDEX_Analogue_channel_2] = (float)ecgDetOut;
                            timestamp++;

                            double rawEEG = sample[AttysComm.INDEX_Analogue_channel_1];
                            double filteredEEG = highpass.filter(rawEEG);
                            if (notch_mains_fundamental != null) {
                                filteredEEG = notch_mains_fundamental.filter(filteredEEG);
                            }
                            if (notch_mains_1st_harmonic != null) {
                                filteredEEG = notch_mains_1st_harmonic.filter(filteredEEG);
                            }
                            if (notch_mains_2nd_harmonic != null) {
                                filteredEEG = notch_mains_2nd_harmonic.filter(filteredEEG);
                            }
                            if (lowpass != null) {
                                filteredEEG = lowpass.filter(filteredEEG);
                            }

                            float beta = (float) (betaHighpass.filter(betaLowpass.filter(filteredEEG)));
                            float alpha = (float) (alphaHighpass.filter(alphaLowpass.filter(filteredEEG)));
                            float theta = (float) (thetaHighpass.filter(thetaLowpass.filter(filteredEEG)));
                            float delta = (float) (deltaLowpass.filter(filteredEEG));
                            float gamma = (float) (gammaHighpass.filter(filteredEEG));

                            dataRecorder.saveData(
                                    (float) rawEEG,
                                    (float) filteredEEG,
                                    beta,
                                    alpha,
                                    theta,
                                    delta,
                                    gamma);

                            // fragements!
                            // add yours here
                            if (betaRatioFragment != null) {
                                betaRatioFragment.addValue((float) filteredEEG);
                            }
                            if (barGraphFragment != null) {
                                barGraphFragment.addValue(delta, theta, alpha, beta, gamma);
                            }

                            // now plotting it in the main window
                            int nRealChN = 0;
                            tmpMin[nRealChN] = -max;
                            tmpMax[nRealChN] = max;
                            tmpTick[nRealChN] = ytick;
                            tmpLabels[nRealChN] = "EEG";
                            tmpSample[nRealChN++] = (float) filteredEEG;

                            if (showGamma) {
                                tmpMin[nRealChN] = -max;
                                tmpMax[nRealChN] = max;
                                tmpTick[nRealChN] = ytick;
                                tmpLabels[nRealChN] = "Gamma";
                                tmpSample[nRealChN++] = gamma;
                            }

                            if (showBeta) {
                                tmpMin[nRealChN] = -max;
                                tmpMax[nRealChN] = max;
                                tmpTick[nRealChN] = ytick;
                                tmpLabels[nRealChN] = "Beta";
                                tmpSample[nRealChN++] = beta;
                            }

                            if (showAlpha) {
                                tmpMin[nRealChN] = -max;
                                tmpMax[nRealChN] = max;
                                tmpTick[nRealChN] = ytick;
                                tmpLabels[nRealChN] = "Alpha";
                                tmpSample[nRealChN++] = alpha;
                            }

                            if (showTheta) {
                                tmpMin[nRealChN] = -max;
                                tmpMax[nRealChN] = max;
                                tmpTick[nRealChN] = ytick;
                                tmpLabels[nRealChN] = "Theta";
                                tmpSample[nRealChN++] = theta;
                            }

                            if (showDelta) {
                                tmpMin[nRealChN] = -max;
                                tmpMax[nRealChN] = max;
                                tmpTick[nRealChN] = ytick;
                                tmpLabels[nRealChN] = "Delta";
                                tmpSample[nRealChN++] = delta;
                            }

                            if (infoView != null) {
                                if (ygapForInfo == 0) {
                                    ygapForInfo = infoView.getInfoHeight();
                                    if ((Log.isLoggable(TAG, Log.DEBUG)) && (ygapForInfo > 0)) {
                                        Log.d(TAG, "ygap=" + ygapForInfo);
                                    }
                                }
                            }

                            if (realtimePlotView != null) {
                                realtimePlotView.addSamples(Arrays.copyOfRange(tmpSample, 0, nRealChN),
                                        Arrays.copyOfRange(tmpMin, 0, nRealChN),
                                        Arrays.copyOfRange(tmpMax, 0, nRealChN),
                                        Arrays.copyOfRange(tmpTick, 0, nRealChN),
                                        Arrays.copyOfRange(tmpLabels, 0, nRealChN),
                                        ygapForInfo);
                            }
                        }
                    }
                    if (realtimePlotView != null) {
                        realtimePlotView.stopAddSamples();
                    }
                }
            }
        }
    }


    @Override
    public void onBackPressed() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Back button pressed");
        }
        killAttysComm();
        Intent startMain = new Intent(Intent.ACTION_MAIN);
        startMain.addCategory(Intent.CATEGORY_HOME);
        startActivity(startMain);
    }


    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);

        setContentView(R.layout.main_activity_layout);

        Toolbar myToolbar = findViewById(R.id.my_toolbar);
        setSupportActionBar(myToolbar);

        progress = findViewById(R.id.indeterminateBar);

        highpass = new Butterworth();
        betaHighpass = new Butterworth();
        betaLowpass = new Butterworth();
        alphaHighpass = new Butterworth();
        alphaLowpass = new Butterworth();
        thetaHighpass = new Butterworth();
        thetaLowpass = new Butterworth();
        deltaLowpass = new Butterworth();
        gammaHighpass = new Butterworth();
        gain = DEFAULT_GAIN;
    }

    // this is called whenever the app is starting or re-starting
    @Override
    public void onStart() {
        super.onStart();

        startDAQ();

    }

    private void noAttysFoundAlert() {
        alertDialog = new AlertDialog.Builder(this)
                .setTitle("No Attys found or bluetooth disabled")
                .setMessage("Before you can use the Attys you need to pair it with this device.")
                .setPositiveButton("Configure bluetooth", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        Intent i = new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
                        startActivity(i);
                    }
                })
                .setNeutralButton("Buy an Attys at: www.attys.tech", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String url = "https://www.attys.tech";
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setData(Uri.parse(url));
                        startActivity(i);
                        finish();
                    }
                })
                .setNegativeButton("Exit", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        finish();
                    }
                })
                .show();
    }


    public void startDAQ() {

        BluetoothDevice btAttysDevice = AttysComm.findAttysBtDevice();
        if (btAttysDevice == null) {
            noAttysFoundAlert();
        }

        attysComm = new AttysComm();
        attysComm.registerMessageListener(messageListener);
        attysComm.registerDataListener(dataListener);
        attysComm.setFullOrPartialData(AttysComm.PARTIAL_DATA);

        getsetAttysPrefs();

        attysComm.setAdc_samplingrate_index(samplingRate);

        // mains filter
        notch_mains_fundamental = new Butterworth();
        notch_mains_fundamental.bandStop(notchOrder,
                attysComm.getSamplingRateInHz(), powerlineHz, notchBW);
        if ((powerlineHz * 2) < ((float)samplingRate / 2)) {
            notch_mains_1st_harmonic = new Butterworth();
            notch_mains_1st_harmonic.bandStop(notchOrder,
                    attysComm.getSamplingRateInHz(), powerlineHz * 2, notchBW);
        } else {
            notch_mains_1st_harmonic = null;
        }
        if ((powerlineHz * 3) < ((float)samplingRate / 2)) {
            notch_mains_2nd_harmonic = new Butterworth();
            notch_mains_2nd_harmonic.bandStop(notchOrder,
                    attysComm.getSamplingRateInHz(), powerlineHz * 3, notchBW);
        } else {
            notch_mains_2nd_harmonic = null;
        }

        // general lowpass filter
        if (allChLowpassF < ((float)samplingRate / 2)) {
            lowpass = new Butterworth();
            lowpass.lowPass(2, attysComm.getSamplingRateInHz(), allChLowpassF);
        } else {
            lowpass = null;
        }

        // highpass filter
        highpass.highPass(2, attysComm.getSamplingRateInHz(), allChHighpassF);

        gammaHighpass.highPass(2, attysComm.getSamplingRateInHz(), gammaFlow);

        // for beta waves
        betaHighpass.highPass(2, attysComm.getSamplingRateInHz(), betaFlow);
        betaLowpass.lowPass(2, attysComm.getSamplingRateInHz(), betaFhigh);

        // for alpha waves
        alphaHighpass.highPass(2, attysComm.getSamplingRateInHz(), alphaFlow);
        alphaLowpass.lowPass(2, attysComm.getSamplingRateInHz(), alphaFhigh);

        // for theta waves
        thetaHighpass.highPass(2, attysComm.getSamplingRateInHz(), thetaFlow);
        thetaLowpass.lowPass(2, attysComm.getSamplingRateInHz(), thetaFhigh);

        // for delta waves
        deltaLowpass.lowPass(2, attysComm.getSamplingRateInHz(), deltaFhigh);

        realtimePlotView = findViewById(R.id.realtimeplotview);
        realtimePlotView.setMaxChannels(15);
        realtimePlotView.init();

        infoView = findViewById(R.id.infoview);
        infoView.setZOrderOnTop(true);
        infoView.setZOrderMediaOverlay(true);

        stimulusView1 = findViewById(R.id.stimulusview1);
        stimulusView2 = findViewById(R.id.stimulusview2);

        attysComm.start();

        timer = new Timer();
        updatePlotTask = new UpdatePlotTask();
        timer.schedule(updatePlotTask, 0, REFRESH_IN_MS);
    }

    private void killAttysComm() {

        if (timer != null) {
            timer.cancel();
            timer.purge();
            timer = null;
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Killed timer");
            }
        }

        if (updatePlotTask != null) {
            updatePlotTask.cancel();
            updatePlotTask = null;
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Killed update Plot Task");
            }
        }

        if (attysComm != null) {
            attysComm.stop();
            attysComm = null;
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Killed AttysComm");
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Destroy!");
        }
        killAttysComm();
        if (alertDialog != null) {
            if (alertDialog.isShowing()) {
                alertDialog.dismiss();
            }
        }
        alertDialog = null;
    }

    @Override
    protected void onRestart() {
        super.onRestart();

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Restarting");
        }
        killAttysComm();
    }


    @Override
    public void onPause() {
        super.onPause();

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Paused");
        }

    }


    @Override
    public void onStop() {
        super.onStop();

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Stopped");
        }

        killAttysComm();

        // Fragments
        // this stops recording in the fragment
        // this is especially important for fragements
        // which generate a stimulus
        // otherwise they might run in the background
        if (epFragment != null) {
            epFragment.stopSweeps();
        }

    }


    private void enterFilename() {

        final EditText filenameEditText = new EditText(this);
        filenameEditText.setSingleLine(true);

        filenameEditText.setHint("");
        filenameEditText.setText(dataFilename);

        new AlertDialog.Builder(this)
                .setTitle("Enter filename")
                .setMessage("Enter the filename of the data textfile")
                .setView(filenameEditText)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        dataFilename = filenameEditText.getText().toString();
                        dataFilename = dataFilename.replaceAll("[^a-zA-Z0-9.-]", "_");
                        if (!dataFilename.contains(".")) {
                            switch (dataSeparator) {
                                case DataRecorder.DATA_SEPARATOR_COMMA:
                                    dataFilename = dataFilename + ".csv";
                                    break;
                                case DataRecorder.DATA_SEPARATOR_SPACE:
                                    dataFilename = dataFilename + ".dat";
                                    break;
                                case DataRecorder.DATA_SEPARATOR_TAB:
                                    dataFilename = dataFilename + ".tsv";
                            }
                        }
                        setRecColour(Color.GREEN);
                        Toast.makeText(getApplicationContext(),
                                "Press rec to record to '" + dataFilename + "'",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        dataFilename = null;
                        setRecColour(Color.GRAY);
                    }
                })
                .show();
    }


    private void shareData() {

        final List files = new ArrayList();
        final String[] list = getBaseContext().getExternalFilesDir(null).list();
        if (list == null) return;
        if (files == null) return;
        for (String file : list) {
            if (files != null) {
                if (file != null) {
                    files.add(file);
                }
            }
        }

        final ListView listview = new ListView(this);
        ArrayAdapter adapter = new ArrayAdapter(this,
                android.R.layout.simple_list_item_multiple_choice,
                files);
        listview.setAdapter(adapter);
        listview.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);

        listview.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                view.setSelected(true);
            }
        });

        new AlertDialog.Builder(this)
                .setTitle("Share")
                .setMessage("Select filename(s)")
                .setView(listview)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        SparseBooleanArray checked = listview.getCheckedItemPositions();
                        Intent sendIntent = new Intent();
                        sendIntent.setAction(Intent.ACTION_SEND_MULTIPLE);
                        ArrayList<Uri> files = new ArrayList<>();
                        if (null != list) {
                            for (int i = 0; i < listview.getCount(); i++) {
                                if (checked.get(i)) {
                                    String filename = list[i];
                                    File fp = new File(getBaseContext().getExternalFilesDir(null), filename);
                                    final Uri u = FileProvider.getUriForFile(
                                            getBaseContext(),
                                            getApplicationContext().getPackageName() + ".fileprovider",
                                            fp);
                                    files.add(u);
                                    Log.d(TAG, "filename=" + filename);
                                }
                            }
                        }
                        sendIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, files);
                        sendIntent.setType("text/*");
                        sendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(sendIntent, "Send your files"));
                    }
                })
                .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                    }
                })
                .show();

        if (listview != null) {
            ViewGroup.LayoutParams layoutParams = listview.getLayoutParams();
            Screensize screensize = new Screensize(getWindowManager());
            layoutParams.height = screensize.getHeightInPixels() / 2;
            listview.setLayoutParams(layoutParams);
        }

    }


    private void setRecColour(int c) {
        if (null == menuItemRec) return;
        SpannableString s = new SpannableString(menuItemRec.getTitle());
        s.setSpan(new ForegroundColorSpan(c), 0, s.length(), 0);
        menuItemRec.setTitle(s);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main_menu_attyseeg, menu);

        menuItemEnterFilename = menu.findItem(R.id.enterFilename);
        menuItemPref = menu.findItem(R.id.preferences);
        menuItemRec = menu.findItem(R.id.toggleRec);
        menuItemBrowser = menu.findItem(R.id.filebrowser);
        menuItemSource = menu.findItem(R.id.sourcecode);

        setRecColour(Color.GRAY);

        return true;
    }

    private void enableMenuitems(boolean doit) {
        menuItemPref.setEnabled(doit);
        menuItemEnterFilename.setEnabled(doit);
        menuItemSource.setEnabled(doit);
        menuItemBrowser.setEnabled(doit);
    }

    private void toggleRec() {
        if (dataRecorder.isRecording()) {
            dataRecorder.stopRec();
            setRecColour(Color.GRAY);
            enableMenuitems(true);
        } else {
            if (dataFilename != null) {
                File file = new File(getBaseContext().getExternalFilesDir(null), dataFilename.trim());
                dataRecorder.setDataSeparator(dataSeparator);
                if (file.exists()) {
                    Toast.makeText(getApplicationContext(),
                            "File exists already. Enter a different one.",
                            Toast.LENGTH_LONG).show();
                    enableMenuitems(true);
                    return;
                }
                try {
                    dataRecorder.startRec(file);
                } catch (Exception e) {
                    Toast.makeText(getApplicationContext(),
                            "Could not save file.",
                            Toast.LENGTH_LONG).show();
                    Log.d(TAG, "Could not open data file: "+file.getAbsolutePath(), e);
                    return;
                }
                if (dataRecorder.isRecording()) {
                    setRecColour(Color.RED);
                    enableMenuitems(false);
                    Log.d(TAG, "Saving to " + file.getAbsolutePath());
                }
            } else {
                enableMenuitems(true);
                Toast.makeText(getApplicationContext(),
                        "To record enter a filename first", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        switch (item.getItemId()) {

            case R.id.preferences:
                Intent intent = new Intent(this, PrefsActivity.class);
                startActivity(intent);
                return true;

            case R.id.toggleRec:
                toggleRec();
                return true;

            case R.id.Ch1gain200:
                gain = DEFAULT_GAIN / 2;
                return true;

            case R.id.Ch1gain500:
                gain = DEFAULT_GAIN;
                return true;

            case R.id.Ch1gain1000:
                gain = DEFAULT_GAIN * 2;
                return true;

            case R.id.enterFilename:
                enterFilename();
                return true;

            case R.id.showBeta:
                showBeta = !showBeta;
                item.setChecked(showBeta);
                return true;

            case R.id.showAlpha:
                showAlpha = !showAlpha;
                item.setChecked(showAlpha);
                return true;

            case R.id.showTheta:
                showTheta = !showTheta;
                item.setChecked(showTheta);
                return true;

            case R.id.showDelta:
                showDelta = !showDelta;
                item.setChecked(showDelta);
                return true;

            case R.id.showGamma:
                showGamma = !showGamma;
                item.setChecked(showGamma);
                return true;

            case R.id.plotWindowEP:

                deleteFragmentWindow();
                // Create a new Fragment to be placed in the activity layout
                epFragment = new EPFragment();
                epFragment.setSamplingrate(attysComm.getSamplingRateInHz());
                epFragment.setStimulusView1(stimulusView1);
                epFragment.setStimulusView2(stimulusView2);
                epFragment.setPowerlineF(powerlineHz);
                // Add the fragment to the 'fragment_container' FrameLayout
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Adding AEP fragment");
                }
                getSupportFragmentManager().beginTransaction()
                        .add(R.id.fragment_plot_container,
                                epFragment,
                                "epFragment")
                        .commit();
                showPlotFragment();
                return true;

            case R.id.plotWindowFastSlow:

                deleteFragmentWindow();
                // Create a new Fragment to be placed in the activity layout
                betaRatioFragment = new FastSlowRatioFragment();
                betaRatioFragment.setSamplingrate(attysComm.getSamplingRateInHz());
                // Add the fragment to the 'fragment_container' FrameLayout
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Adding beta ratio fragment");
                }
                getSupportFragmentManager().beginTransaction()
                        .add(R.id.fragment_plot_container,
                                betaRatioFragment,
                                "betaRatioFragment")
                        .commit();
                showPlotFragment();
                return true;

            case R.id.plotWindowBarGraph:

                deleteFragmentWindow();
                // Create a new Fragment to be placed in the activity layout
                barGraphFragment = new BarGraphFragment();
                barGraphFragment.setSamplingRate(attysComm.getSamplingRateInHz());
                // Add the fragment to the 'fragment_container' FrameLayout
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Adding bar fragment");
                }
                getSupportFragmentManager().beginTransaction()
                        .add(R.id.fragment_plot_container,
                                barGraphFragment,
                                "barGraphFragment")
                        .commit();
                showPlotFragment();
                return true;

            case R.id.plotWindowOff:
                hidePlotFragment();
                deleteFragmentWindow();
                return true;

            case R.id.filebrowser:
                shareData();
                return true;

            case R.id.sourcecode:
                String url = "https://github.com/glasgowneuro/AttysEEG";
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setData(Uri.parse(url));
                startActivity(i);
                return true;

            default:
                // If we got here, the user's action was not recognized.
                // Invoke the superclass to handle it.
                return super.onOptionsItemSelected(item);

        }
    }


    private void showPlotFragment() {

        Screensize screensize = new Screensize(getWindowManager());

        FrameLayout frameLayout = findViewById(R.id.mainplotlayout);
        frameLayout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT, 1.0F));

        frameLayout = findViewById(R.id.fragment_plot_container);
        frameLayout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT, 0.5F));

    }

    private void hidePlotFragment() {
        FrameLayout frameLayout = findViewById(R.id.mainplotlayout);
        frameLayout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT, 0.0f));
    }


    private synchronized void deleteFragmentWindow() {
        List<Fragment> fragments = getSupportFragmentManager().getFragments();
        if (fragments != null) {
            if (!(fragments.isEmpty())) {
                for (Fragment fragment : fragments) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        if (fragment != null) {
                            Log.d(TAG, "Removing fragment: " + fragment.getTag());
                        }
                    }
                    if (fragment != null) {
                        getSupportFragmentManager().beginTransaction().remove(fragment).commit();
                    }
                }
            }
        }
        epFragment = null;
        betaRatioFragment = null;
        stimulusView1.setVisibility(View.INVISIBLE);
        stimulusView2.setVisibility(View.INVISIBLE);
    }


    private void getsetAttysPrefs() {
        byte mux;

        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Setting preferences");
        }
        SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(this);

        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        mux = AttysComm.ADC_MUX_NORMAL;
        byte adcgain = (byte) (Integer.parseInt(prefs.getString("gainpref", "0")));
        attysComm.setAdc1_gain_index(adcgain);
        attysComm.setAdc0_mux_index(mux);
        attysComm.setAdc2_gain_index(adcgain);
        attysComm.setAdc1_mux_index(mux);

        byte data_separator = (byte) (Integer.parseInt(prefs.getString("data_separator", "0")));
        dataRecorder.setDataSeparator(data_separator);

        powerlineHz = Float.parseFloat(prefs.getString("powerline", "50"));
        if (powerlineHz > 60) powerlineHz = 60;
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "powerline=" + powerlineHz);
        }

        samplingRate = (byte) Integer.parseInt(prefs.getString("samplingrate", "1"));
        if (samplingRate < AttysComm.ADC_RATE_250HZ) samplingRate = AttysComm.ADC_RATE_250HZ;
        if (samplingRate > AttysComm.ADC_RATE_500Hz) samplingRate = AttysComm.ADC_RATE_500Hz;
        attysComm.setAdc_samplingrate_index(samplingRate);
    }

}
