package tech.glasgowneuro.attysep;

import android.graphics.Color;
import android.graphics.Paint;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
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

import java.io.IOException;

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

    private ClickSoundRunnable clickSoundRunnable = null;

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
        //aepPlot.setRangeBoundaries(-10, 10, BoundaryMode.FIXED);
        aepPlot.setDomainBoundaries(0, tmax, BoundaryMode.FIXED);
        aepPlot.addSeries(epHistorySeries,
                new LineAndPointFormatter(
                        Color.rgb(100, 255, 255), null, null, null));
        aepPlot.setDomainLabel("t/sec");
        aepPlot.setRangeLabel("");

        for (int i = 0; i < nSamples; i++) {
            epHistorySeries.addLast(i * (1.0F / ((float) samplingRate)), 0.0);
        }

        index = 0;
        nSweeps = 1;

        clickSoundRunnable = new ClickSoundRunnable();

        /**
         DisplayMetrics metrics = new DisplayMetrics();
         getActivity().getWindowManager().getDefaultDisplay().getMetrics(metrics);
         int width = metrics.widthPixels;
         int height = metrics.heightPixels;
         if ((height > 1000) && (width > 1000)) {
         aepPlot.setDomainStep(StepMode.INCREMENT_BY_VAL, 25);
         aepPlot.setRangeStep(StepMode.INCREMENT_BY_VAL, 25);
         } else {
         aepPlot.setDomainStep(StepMode.INCREMENT_BY_VAL, 50);
         aepPlot.setRangeStep(StepMode.INCREMENT_BY_VAL, 50);
         }
         **/

        ready = true;
    }


    private class ClickSoundRunnable implements Runnable {

        private AudioTrack sound;
        private byte[] rawAudio;
        int audioSamplingRate = 44100;
        int audio2eegRatio = audioSamplingRate / samplingRate;
        int nAudioSamples = (nSamples-1) * audio2eegRatio;
        int clickduration = audioSamplingRate / 1000; // 1ms

        public ClickSoundRunnable() {
            sound = new AudioTrack(AudioManager.STREAM_MUSIC,
                    audioSamplingRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_8BIT,
                    nAudioSamples,
                    AudioTrack.MODE_STATIC);
            rawAudio = new byte[nAudioSamples];
            for(int i=0;i<nAudioSamples;i++) {
                rawAudio[i] = (byte)0x80;
            }
            for(int i=0;i<clickduration;i++) {
                rawAudio[i] = (byte)0x00;
                rawAudio[i+clickduration] = (byte)0xff;
            }
            sound.write(rawAudio, 0, rawAudio.length);
        }

        @Override
        public void run() {
            playSound();
        }

        private synchronized void playSound() {
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

        public synchronized void release() {
            sound.release();
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

        view = inflater.inflate(R.layout.eapfragment, container, false);

        // setup the APR Levels plot:
        aepPlot = (XYPlot) view.findViewById(R.id.bpmPlotView);
        sweepNoText = (TextView) view.findViewById(R.id.bpmTextView);
        toggleButtonDoSweep = (ToggleButton) view.findViewById(R.id.doSweeps);
        toggleButtonDoSweep.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                doSweeps = isChecked;
                if (doSweeps) {
                    acceptData = true;
                }
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

    public synchronized void addValue(final float v) {

        if (!ready) return;

        if (!acceptData) return;

        if (index == 0) {
            new Thread(clickSoundRunnable).start();
        }

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
        if (index >= nSamples) {
            index = 0;
            nSweeps++;
            if (!doSweeps) {
                acceptData = false;
            }
        }
    }

    public void redraw() {
        if (aepPlot != null) {
            aepPlot.redraw();
        }
    }
}
