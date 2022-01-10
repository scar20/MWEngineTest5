package com.scarette.mwenginetest5;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import android.Manifest;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;

import java.util.List;
import java.util.Vector;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import nl.igorski.mwengine.MWEngine;
import nl.igorski.mwengine.core.*;


public class MainActivity extends AppCompatActivity {

    /**
     * IMPORTANT : when creating native layer objects through JNI it
     * is important to remember that when the Java references go out of scope
     * (and thus are finalized by the garbage collector), the SWIG interface
     * will invoke the native layer destructors. As such we hold strong
     * references to JNI Objects during the application lifetime
     */
    private Limiter             _limiter;
    private LPFHPFilter _lpfhpf;

    private SampledInstrument _sampler;

    private MWEngine _engine;
    private SequencerController _sequencerController;

    private Vector<SampleEvent> _samplesVector = new Vector<>();

    private ReverbSM reverb;
    private Metronome metronome;
    private ChannelGroup track1;

    private int maxSampleCount = 32;
    private int slength;
    private int smillis;

    private boolean _inited           = false;

    // AAudio is only supported from Android 8/Oreo onwards.
    private boolean _supportsAAudio     = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O;
    private Drivers.types  _audioDriver = _supportsAAudio ? Drivers.types.AAUDIO : Drivers.types.OPENSL;

    private int SAMPLE_RATE;
    private int BUFFER_SIZE;
    private int OUTPUT_CHANNELS = 2; // 1 = mono, 2 = stereo

    private static String LOG_TAG = "MWENGINE"; // logcat identifier
    private static int PERMISSIONS_CODE = 8081981;

    /* public methods */

    /**
     * Called when the activity is created. This also fires
     * on screen orientation changes.
     */
    @Override
    public void onCreate( Bundle savedInstanceState ) {
        super.onCreate( savedInstanceState );
        setContentView( R.layout.activity_main );

        // these may not necessarily all be required for your use case (e.g. if you're not recording
        // from device audio inputs or reading/writing files) but are here for self-documentation

        if ( android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M ) {
            String[] PERMISSIONS = {
                    Manifest.permission.RECORD_AUDIO, // RECORD_AUDIO must be granted prior to engine.start()
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            };
            // Check if we have all the necessary permissions, if not: prompt user
            int permission = checkSelfPermission( Manifest.permission.RECORD_AUDIO );
            if ( permission == PackageManager.PERMISSION_GRANTED )
                init();
            else
                requestPermissions( PERMISSIONS, PERMISSIONS_CODE );
        }
    }

    @TargetApi( Build.VERSION_CODES.M )
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult( requestCode, permissions, grantResults );
        if ( requestCode != PERMISSIONS_CODE ) return;
        for ( int i = 0; i < permissions.length; i++ ) {
            String permission = permissions[ i ];
            int grantResult   = grantResults[ i ];
            if ( permission.equals( Manifest.permission.RECORD_AUDIO ) && grantResult == PackageManager.PERMISSION_GRANTED ) {
                init();
            } else {
                requestPermissions( new String[]{ Manifest.permission.RECORD_AUDIO }, PERMISSIONS_CODE );
            }
        }
    }



    /**
     * Called when screen resizes / orientation changes. We handle this manually as we do not want onDestroy()
     * to fire for this occasion (the audio engine would otherwise be disposed, which is not what we want
     * for this scenario (see :configChanges directive in AndroidManifest)
     */
    @Override
    public void onConfigurationChanged( Configuration newConfig ) {
        super.onConfigurationChanged( newConfig );
        Log.d( LOG_TAG, "MWEngineActivity::onConfigurationChanged, new orientation: " + newConfig.orientation );
    }

    /**
     * Called when the activity is destroyed. This should not fire on screen resize/orientation
     * changes. See AndroidManifest.xml for the appropriate :configChanges directive on the activity.
     * On actual destroy, we clean up the engine's thread and memory allocated outside of the Java environment.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d( LOG_TAG, "MWEngineActivity::onDestroy" );
        flushSong();        // free memory allocated by song
        _engine.dispose();  // dispose the engine
    }

    private void init() {
        Log.d( LOG_TAG, "MWEngineActivity::init, had existing _inited state: " + _inited );

        if ( _inited )
            return;

        // STEP 1 : preparing the native audio engine

        _engine = new MWEngine( new StateObserver() );


        MWEngine.optimizePerformance( this );

        // get the recommended buffer size for this device (NOTE : lower buffer sizes may
        // provide lower latency, but make sure all buffer sizes are powers of two of
        // the recommended buffer size (overcomes glitching in buffer callbacks )
        // getting the correct sample rate upfront will omit having audio going past the system
        // resampler reducing overall latency

        BUFFER_SIZE = MWEngine.getRecommendedBufferSize( getApplicationContext() );
        SAMPLE_RATE = MWEngine.getRecommendedSampleRate( getApplicationContext() );

        _engine.createOutput( SAMPLE_RATE, BUFFER_SIZE, OUTPUT_CHANNELS, _audioDriver );



        setupSong();

        // STEP 3 : start your engine!
        // Starts engines render thread (NOTE: sequencer is still paused)
        // this ensures that audio will be output as appropriate (e.g. when
        // playing live events / starting sequencer and playing the song)

        _engine.start();

        // STEP 4 : attach event handlers to the UI elements (see main.xml layout)

        ((Spinner) findViewById( R.id.SampleSpinner)).setOnItemSelectedListener( new SampleChangeHandler() );

        ((SwitchCompat) findViewById(R.id.ReverbSwitch)).setOnClickListener(new ReverbSwitchHandler());

        ((SwitchCompat) findViewById(R.id.MetronomeSwitch)).setOnClickListener(new MetronomeSwitchHandler());

        (( SeekBar ) findViewById( R.id.MetronomeSpeedSlider )).setOnSeekBarChangeListener( new MetronomeChangeHandler() );

        (( SeekBar ) findViewById( R.id.VolumeSlider )).setOnSeekBarChangeListener( new VolumeChangeHandler() );

        ((Button) findViewById( R.id.oneshot_button)).setOnTouchListener( new OneShotHandler() );

        ((Button) findViewById( R.id.oneshotstop_button)).setOnTouchListener( new OneShotStopHandler() );

        _inited = true;
    }

    /* protected methods */

    protected void setupSong() {

        // cache some of the engines properties

        final ProcessingChain masterBus = _engine.getMasterBusProcessors();

        // create a lowpass filter to catch all low rumbling and a limiter to prevent clipping of output :)

        _lpfhpf  = new LPFHPFilter(( float )  MWEngine.SAMPLE_RATE, 55, OUTPUT_CHANNELS );
        _limiter = new Limiter(0.5f, 1.0f, 1.0f);

        masterBus.addProcessor( _lpfhpf );
//        masterBus.addProcessor( _limiter );

        // STEP 2 : let's create some instruments =D

        _sampler = new SampledInstrument();

        for (int i = 0; i < maxSampleCount; i++) {
            final SampleEvent ev = new SampleEvent(_sampler);
            _samplesVector.add(ev);
        }
        Log.d( LOG_TAG, "setupSong() _sampleVector.size: " + _samplesVector.size());
        Log.d( LOG_TAG, "setupSong() _sampleVector.get(0): " + _samplesVector.get(0));

//        Log.d( LOG_TAG, "setupSong() creating new metronome");
        metronome = new Metronome();
//        Log.d( LOG_TAG, "setupSong() new metronome created");
        _sampler.getAudioChannel().getProcessingChain().addProcessor(_limiter);

        reverb = new ReverbSM();

        track1 = new ChannelGroup();
        track1.addAudioChannel(_sampler.getAudioChannel());
        _engine.addChannelGroup(track1);

    }

    protected void flushSong() {
        // this ensures that Song resources currently in use by the engine are released

        Log.d( LOG_TAG, "MWEngineActivity::flushSong" );

        _engine.stop();

        // calling 'delete()' on a BaseAudioEvent invokes the
        // native layer destructor (and removes it from the sequencer)

        for (final BaseAudioEvent event : _samplesVector) {
            event.getInstrument().delete();
            event.delete();
        }

        // detach all processors from engine's master bus

        _engine.getMasterBusProcessors().reset();

        // calling 'delete()' on all instruments invokes the native layer destructor
        // (and frees memory allocated to their resources, e.g. AudioChannels, Processors)

        _sampler.delete();
        _samplesVector.clear();


        // allow these to be garbage collected

        _sampler = null;
        _samplesVector = null;

        // and these (garbage collection invokes native layer destructors, so we'll let
        // these processors be cleared lazily)

        _lpfhpf = null;
        _limiter = null;
        reverb = null;

        // flush sample memory allocated in the SampleManager
        SampleManager.flushSamples();
    }

    @Override
    public void onWindowFocusChanged( boolean hasFocus ) {
        Log.d( LOG_TAG, "MWEngineActivity::onWindowFocusChanged, has focus: " + hasFocus );

        if ( !hasFocus ) {
            // suspending the app - halt audio rendering in MWEngine Thread to save CPU cycles
            if ( _engine != null )
                _engine.stop();
        }
        else {
            // returning to the app
            if ( !_inited )
                init();          // initialize this example application
            else
                _engine.start(); // resumes audio rendering
        }
    }

    /* event handlers */

    private class SampleChangeHandler implements AdapterView.OnItemSelectedListener {
        public void onItemSelected(AdapterView<?> parent, View view, int pos,long id) {

            String name = "one";
            if (pos == 0) {
                if (SampleManager.hasSample("one"))
                    SampleManager.removeSample("one", true);
                Log.d(LOG_TAG, "//////// SoundChangeHandler sample still exist? : "
                        + SampleManager.hasSample("one"));
                loadWAVAsset( "bach2_48.wav", "one");
                Log.d(LOG_TAG, "//////// SoundChangeHandler new bach sample loaded? : "
                        + SampleManager.hasSample("one"));
            } else if (pos == 1) {
                if (SampleManager.hasSample("one"))
                    SampleManager.removeSample("one", true);
                Log.d(LOG_TAG, "//////// SoundChangeHandler sample still exist? : "
                        + SampleManager.hasSample("one"));
                loadWAVAsset("bonjour-hello48_16bit.wav", "one");
                Log.d(LOG_TAG, "//////// SoundChangeHandler new bonjour sample loaded? : "
                        + SampleManager.hasSample("one"));
            }
            slength = SampleManager.getSampleLength("one");
            smillis = BufferUtility.bufferToMilliseconds(slength, SAMPLE_RATE);
            metronome.calcOverlap();
            for (SampleEvent ev : _samplesVector)
                ev.setSample(SampleManager.getSample(name));

        }
        @Override
        public void onNothingSelected(AdapterView<?> arg0) {}
    }

    private class ReverbSwitchHandler implements View.OnClickListener {
        @Override
        public void onClick(View view) {
            if (((SwitchCompat)view).isChecked()) {
                _sampler.getAudioChannel().getProcessingChain().addProcessor(reverb);
            } else {
                _sampler.getAudioChannel().getProcessingChain().removeProcessor(reverb);
            }
        }
    }

    private class MetronomeSwitchHandler implements View.OnClickListener {
        @Override
        public void onClick(View view) {
            if (((SwitchCompat)view).isChecked()) {
                metronome.start();
            } else {
                metronome.stop();
            }
        }
    }

    private class MetronomeChangeHandler implements SeekBar.OnSeekBarChangeListener {
        public void onProgressChanged( SeekBar seekBar, int progress, boolean fromUser ) {
            float prog =  progress / 100f;
            final float minTempo = 5f;     // minimum allowed tempo is 5 BPM
            final float maxTempo = 1000f;    // maximum allowed tempo is 1000 BPM
            final float newTempo = (float) (prog * prog) * (maxTempo - minTempo) + minTempo;
            long millis = (long) (60000f / newTempo);
            //           Log.d(LOG_TAG, "Metro rate: " + newTempo + "  millis: " + millis);
            metronome.setTime(millis);
            metronome.calcOverlap();

        }
        public void onStartTrackingTouch( SeekBar seekBar ) {}
        public void onStopTrackingTouch ( SeekBar seekBar ) {}
    }

    private class VolumeChangeHandler implements SeekBar.OnSeekBarChangeListener {
        public void onProgressChanged( SeekBar seekBar, int progress, boolean fromUser ) {
            track1.setVolume( progress / 100f );
        }
        public void onStartTrackingTouch( SeekBar seekBar ) {}
        public void onStopTrackingTouch ( SeekBar seekBar ) {}
    }

    private class OneShotHandler implements View.OnTouchListener {
        private int count = 0;
        @Override
        public boolean onTouch(View view, MotionEvent motionEvent) {
            if (motionEvent.getAction() == MotionEvent.ACTION_DOWN) {
//                Log.d( LOG_TAG, "_sampleVector.get("+count+"): " + _samplesVector.get(count));
                _samplesVector.get(count).play();
                count++;
                count = count % maxSampleCount;
            }
            return false;
        }
    }

    private class OneShotStopHandler implements View.OnTouchListener {
        @Override
        public boolean onTouch(View view, MotionEvent motionEvent) {
            if (motionEvent.getAction() == MotionEvent.ACTION_DOWN) {
 //               for (SampleEvent ev : _samplesVector) ev.stop();
                for (int i = 0; i < maxSampleCount; i++) {
                    _samplesVector.get(i).stop();
                }
            }
            return false;
        }
    }

    private class Metronome {

        private ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ScheduledFuture<?> task;
        private long delay = 1500;
        private int count = 0;
        private boolean isRunning = false;

        public void setTime(long time) {
            delay = time;
        }
        public boolean isRunning() { return isRunning; }

        public void start() {
            count = 0; // metro already on, start from main sample standard start/stop
            isRunning = true;
            cycle();
        }
        // First sample (main) already started; start subsequent samples
        public void startAfter() { // metro switched on after sample already playing
            count = 1; // always start from 2d sample, first is main sample standard start/stop
            isRunning = true;
            cycle();
        }

        public void stop() {
            if (isRunning) {
                isRunning = false;
                if(task != null) task.cancel(true);
                cycle();
//                scheduler.schedule(this::cycle, 0l, TimeUnit.MILLISECONDS);
//                for (SampleEvent ev : _samplesVector) ev.stop();
//                for (int i = 0; i < maxSampleCount; i++) { // stop all but first main sample
//                    _samplesVector.get(i).stop();
//                }
            }
        }

        public void cycle() {
            if (isRunning) {
//                Log.d(LOG_TAG, "count: " + count);
                _samplesVector.get(count).play(); // pointers are reset on play
                count++;
                count = count % maxSampleCount;
                task = scheduler.schedule(this::cycle, delay, TimeUnit.MILLISECONDS);
            } else {
//                if(task != null) task.cancel(true);
//                for (SampleEvent ev : _samplesVector) ev.stop();
                for (int i = 0; i < maxSampleCount; i++) {
                    _samplesVector.get(i).stop();
                }
            }
        }

        // attenuation scheme
        // curRangeInMillis / cycle time = num of overlap
        // if overlap > thresh apply attenuation overlap * attenuation
        // max overlap = maxSampleCount
        public void calcOverlap() {
            float overlap = smillis / (float)delay;
            Log.d(LOG_TAG, "sample millis: " + smillis + " cycle time: " + delay + " overlap: " + overlap);
        }

    }

    /* state change message listener */

    private class StateObserver implements MWEngine.IObserver {
        private final Notifications.ids[] _notificationEnums = Notifications.ids.values(); // cache the enumerations (from native layer) as int Array
        public void handleNotification( final int aNotificationId ) {
            switch ( _notificationEnums[ aNotificationId ]) {
                case ERROR_HARDWARE_UNAVAILABLE:
                    Log.d( LOG_TAG, "ERROR : received driver error callback from native layer" );
                    _engine.dispose();
                    break;
                case MARKER_POSITION_REACHED:
                    Log.d( LOG_TAG, "Marker position has been reached" );
                    break;
                case RECORDING_COMPLETED:
                    Log.d( LOG_TAG, "Recording has completed" );
                    break;
            }
        }

        public void handleNotification( final int aNotificationId, final int aNotificationValue ) {
            switch ( _notificationEnums[ aNotificationId ]) {
                case SEQUENCER_POSITION_UPDATED:

                    // for this notification id, the notification value describes the precise buffer offset of the
                    // engine when the notification fired (as a value in the range of 0 - BUFFER_SIZE). using this value
                    // we can calculate the amount of samples pending until the next step position is reached
                    // which in turn allows us to calculate the engine latency

//                    int sequencerPosition = _sequencerController.getStepPosition();
//                    int elapsedSamples    = _sequencerController.getBufferPosition();
//
//                    Log.d( LOG_TAG, "seq. position: " + sequencerPosition + ", buffer offset: " + aNotificationValue +
//                            ", elapsed samples: " + elapsedSamples );
                    break;
                case RECORDED_SNIPPET_READY:
                    runOnUiThread( new Runnable() {
                        public void run() {
                            // we run the saving on a different thread to prevent buffer under runs while rendering audio
                            _engine.saveRecordedSnippet( aNotificationValue ); // notification value == snippet buffer index
                        }
                    });
                    break;
                case RECORDED_SNIPPET_SAVED:
                    Log.d( LOG_TAG, "Recorded snippet " + aNotificationValue + " saved to storage" );
                    break;
            }
        }
    }

    /* private methods */


    /**
     * convenience method to load WAV files packaged in the APK
     * and read their audio content into MWEngine's SampleManager
     *
     * @param assetName {String} assetName filename for the resource in the /assets folder
     * @param sampleName {String} identifier for the files WAV content inside the SampleManager
     */
    private void loadWAVAsset( String assetName, String sampleName ) {
        final Context ctx = getApplicationContext();
        JavaUtilities.createSampleFromAsset(
                sampleName, ctx.getAssets(), ctx.getCacheDir().getAbsolutePath(), assetName
        );
    }
}