package tech.glasgowneuro.attysep;

import android.graphics.Color;
import android.graphics.Paint;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.ToggleButton;

import com.androidplot.xy.BoundaryMode;
import com.androidplot.xy.LineAndPointFormatter;
import com.androidplot.xy.SimpleXYSeries;
import com.androidplot.xy.StepMode;
import com.androidplot.xy.XYPlot;

import java.text.FieldPosition;
import java.text.NumberFormat;
import java.util.Timer;
import java.util.TimerTask;

import uk.me.berndporr.iirj.Butterworth;

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
        sweepNoText = (TextView) view.findViewById(R.id.bpmTextView);
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
