package com.meh;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;

/**
 * Main screen
 */
public class IceplayActivity extends Activity {
    private static final String tag = IceplayActivity.class.getName(); //log tag

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
        final PlaylistRepository playlistRepository =
                new PlaylistRepository(getApplicationContext(),
                        getContentResolver());
        //insert hardcoded station into list of streams
//        long id =
//           playlistRepository.insert("http://broadcast.wnrn.org:8000/wnrn.mp3.m3u", "WNRN", 0);
        long id =
                playlistRepository.insert("http://www.live365.com/play/jtava", "ANR", 0);
        Log.v(tag, id+"");
        final Playable playable = playlistRepository.getNextEntry(id);
        
        //the buttons
        Button play = (Button) findViewById(R.id.playbutton);
        Button stop = (Button) findViewById(R.id.stopbutton);
        play.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
               Log.v(tag, "play click");
               playNow(playable);
            }
        });
        stop.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                Log.v(tag, "stop click");
                Intent intent = new Intent(getApplicationContext(), PlaybackService.class);
                getApplicationContext().stopService(intent);
            }
        });
        

    }
    
    private void playNow(final Playable playable) {
        Intent intent = new Intent(getApplicationContext(), PlaybackService.class);
        intent.setAction(PlaybackService.SERVICE_PLAY_ENTRY);
        intent.putExtra(Playable.PLAYABLE_TYPE, playable);
        getApplicationContext().startService(intent);
      }
}