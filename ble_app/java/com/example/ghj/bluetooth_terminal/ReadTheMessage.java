package com.example.ghj.bluetooth_terminal;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.widget.Toast;

import java.util.HashMap;

/**
 * Created by GHJ on 31/03/2015.
 */
public class ReadTheMessage extends Service implements TextToSpeech.OnInitListener,TextToSpeech.OnUtteranceCompletedListener {

    private TextToSpeech myTTS = null;
    private String msg = "Está Funcionando 2";
    /*
    public int onStartCommand (Intent intent, int flags, int startId){

        msg = intent.getStringExtra("MESSAGE");

        return START_STICKY;
    }
    */

        @Override
    public int onStartCommand (Intent intent, int flags, int startId){
        msg = intent.getExtras().getString("MESSAGE");
        // msg = intent.getStringExtra("MESSAGE");
        return START_STICKY;
    }


    @Override
    public void onCreate() {
        myTTS = new TextToSpeech(this,this);
    }

    @Override
    public void onDestroy() {
        if (myTTS != null) {
            myTTS.stop();
            myTTS.shutdown();
        }
        super.onDestroy();
    }

    // OnInitListener impl
    public void onInit(int status) {
        if (status != TextToSpeech.SUCCESS) {
            Toast.makeText(this, "TTS initialization failed",Toast.LENGTH_SHORT).show();
        }else{
            myTTS.setOnUtteranceCompletedListener(new TextToSpeech.OnUtteranceCompletedListener() {

                @Override
                public void onUtteranceCompleted(String utteranceId) {
                    Log.d(GeofenceUtils.APPTAG, "Inside onUtteranceCompleted2");
                    stopSelf();
                }
            });
            speakOut();
        }
        //onDestroy();
    }

    private void speakOut() {
        HashMap<String, String> map = new HashMap<String, String>();
        map.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "utteranceId");
        myTTS.speak(msg, TextToSpeech.QUEUE_FLUSH, map);
    }

    // OnUtteranceCompletedListener impl
    @Override
    public void onUtteranceCompleted(String uttId) {

    }

    @Override
    public IBinder onBind(Intent arg0) {
        return null;
    }
}