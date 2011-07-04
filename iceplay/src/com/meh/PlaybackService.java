// Copyright 2009 Google Inc.
// Copyright 2011 NPR
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.meh;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;

import com.meh.nprutil.M3uParser;
import com.meh.nprutil.PlaylistParser;
import com.meh.nprutil.PlsParser;
import com.meh.nprutil.StreamProxy;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnBufferingUpdateListener;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnInfoListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.Toast;

/**
 * Handles playback of streams in background.
 */
public class PlaybackService extends Service implements
    OnPreparedListener,
    OnBufferingUpdateListener, OnCompletionListener, OnErrorListener,
    OnInfoListener {

  private static final String LOG_TAG = PlaybackService.class.getName();

  private static final String SERVICE_PREFIX = "org.npr.android.news.";
  public static final String SERVICE_CHANGE_NAME = SERVICE_PREFIX + "CHANGE";
  public static final String SERVICE_CLOSE_NAME = SERVICE_PREFIX + "CLOSE";
  public static final String SERVICE_UPDATE_NAME = SERVICE_PREFIX + "UPDATE";

  public static final String SERVICE_PLAY_SINGLE = SERVICE_PREFIX +
      "PLAY_SINGLE";
  public static final String SERVICE_PLAY_ENTRY = SERVICE_PREFIX + "PLAY_ENTRY";
  public static final String SERVICE_TOGGLE_PLAY = SERVICE_PREFIX +
      "TOGGLE_PLAY";
  public static final String SERVICE_BACK_30 = SERVICE_PREFIX + "BACK_30";
  public static final String SERVICE_SEEK_TO = SERVICE_PREFIX + "SEEK_TO";
  public static final String SERVICE_PLAY_NEXT = SERVICE_PREFIX + "PLAYNEXT";
  public static final String SERVICE_PLAY_PREVIOUS = SERVICE_PREFIX +
      "PLAYPREVIOUS";
  public static final String SERVICE_STATUS = SERVICE_PREFIX + "STATUS";
  public static final String SERVICE_CLEAR_PLAYER = SERVICE_PREFIX +
      "CLEAR_PLAYER";

  public static final String EXTRA_ID = SERVICE_PREFIX + "ID";
  public static final String EXTRA_TITLE = SERVICE_PREFIX + "TITLE";
  public static final String EXTRA_DOWNLOADED = SERVICE_PREFIX + "DOWNLOADED";
  public static final String EXTRA_DURATION = SERVICE_PREFIX + "DURATION";
  public static final String EXTRA_POSITION = SERVICE_PREFIX + "POSITION";
  public static final String EXTRA_SEEK_TO = SERVICE_PREFIX + "SEEK_TO";
  public static final String EXTRA_IS_PLAYING = SERVICE_PREFIX + "IS_PLAYING";

  private MediaPlayer mediaPlayer;
  private boolean isPrepared = false;
  private boolean markedRead;
  // Track whether we ever called start() on the media player so we don't try
  // to reset or release it. This causes a hang (ANR) on Droid X
  // http://code.google.com/p/android/issues/detail?id=959
  private boolean mediaPlayerHasStarted = false;

  private StreamProxy proxy;
  private NotificationManager notificationManager;
  private static final int NOTIFICATION_ID = 1;
  private PlaylistRepository playlist;
  private int startId;
  private String currentAction;
  private Playable current = null;
  private List<String> playlistUrls;
  private int errorCt;

  private TelephonyManager telephonyManager;
  private PhoneStateListener listener;
  private boolean isPausedInCall = false;
  private Intent lastChangeBroadcast;
  private Intent lastUpdateBroadcast;
  private int lastBufferPercent = 0;
  private Thread updateProgressThread;

  // Amount of time to rewind playback when resuming after call 
  private final static int RESUME_REWIND_TIME = 3000;
  private final static int ERROR_RETRY_COUNT = 3;

  private Looper serviceLooper;
  private ServiceHandler serviceHandler;

  private final class ServiceHandler extends Handler {
    public ServiceHandler(Looper looper) {
      super(looper);
    }

    @Override
    public void handleMessage(Message msg) {
      startId = msg.arg1;
      onHandleIntent((Intent) msg.obj);
    }
  }


  @Override
  public void onCreate() {
    super.onCreate();
    mediaPlayer = new MediaPlayer();
    mediaPlayer.setOnBufferingUpdateListener(this);
    mediaPlayer.setOnCompletionListener(this);
    mediaPlayer.setOnErrorListener(this);
    mediaPlayer.setOnInfoListener(this);
    mediaPlayer.setOnPreparedListener(this);
    notificationManager = (NotificationManager) getSystemService(
        Context.NOTIFICATION_SERVICE);
    playlist = new PlaylistRepository(getApplicationContext(),
        getContentResolver());

    Log.d(LOG_TAG, "Playback service created");

    telephonyManager = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
    // Create a PhoneStateListener to watch for off-hook and idle events
    listener = new PhoneStateListener() {
      @Override
      public void onCallStateChanged(int state, String incomingNumber) {
        switch (state) {
          case TelephonyManager.CALL_STATE_OFFHOOK:
          case TelephonyManager.CALL_STATE_RINGING:
            // Phone going off-hook or ringing, pause the player.
            if (isPlaying()) {
              pause();
              isPausedInCall = true;
            }
            break;
          case TelephonyManager.CALL_STATE_IDLE:
            // Phone idle. Rewind a couple of seconds and start playing.
            if (isPausedInCall) {
              isPausedInCall = false;
              seekTo(Math.max(0, getPosition() - RESUME_REWIND_TIME));
              play();
            }
            break;
        }
      }
    };

    // Register the listener with the telephony manager.
    telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE);

    HandlerThread thread = new HandlerThread("PlaybackService:WorkerThread");
    thread.start();

    serviceLooper = thread.getLooper();
    serviceHandler = new ServiceHandler(serviceLooper);
  }

  @Override
  public void onStart(Intent intent, int startId) {
    Log.d(LOG_TAG, "OnStart");
    super.onStart(intent, startId);
    Message message = serviceHandler.obtainMessage();
    message.arg1 = startId;
    message.obj = intent;
    serviceHandler.sendMessage(message);
  }

  protected void onHandleIntent(Intent intent) {
    String action = intent.getAction();
    if (action.equals(SERVICE_PLAY_SINGLE) || action.equals(SERVICE_PLAY_ENTRY)) {
      currentAction = action;
      current = intent.getParcelableExtra(Playable.PLAYABLE_TYPE);
      playCurrent(0);
    } else if (action.equals(SERVICE_TOGGLE_PLAY)) {
      if (isPlaying()) {
        pause();
        // Get rid of the toggle intent, since we don't want it redelivered
        // on restart
        Intent emptyIntent = new Intent(intent);
        emptyIntent.setAction("");
        startService(emptyIntent);
      } else if (current != null) {
        if (isPrepared) {
          play();
        } else {
          playCurrent(0);
        }
      } else {
        currentAction = SERVICE_PLAY_ENTRY;
        errorCt = 0;
        playFirstUnreadEntry();
      }
    } else if (action.equals(SERVICE_BACK_30)) {
      seekRelative(-30000);
    } else if (action.equals(SERVICE_SEEK_TO)) {
      seekTo(intent.getIntExtra(EXTRA_SEEK_TO, 0));
    } else if (action.equals(SERVICE_PLAY_NEXT)) {
      playNextEntry();
    } else if (action.equals(SERVICE_PLAY_PREVIOUS)) {
      playPreviousEntry();
    } else if (action.equals(SERVICE_STATUS)) {
      updateProgress();
    } else if (action.equals(SERVICE_CLEAR_PLAYER)) {
      if (!isPlaying()) {
        stopSelfResult(startId);
      }
    }
  }

  @Override
  public IBinder onBind(Intent intent) {
    Log.w(LOG_TAG, "onBind called, but binding no longer supported.");
    return null;
  }

  private boolean playCurrent(int startingErrorCount) {
    errorCt = startingErrorCount;
    while (errorCt < ERROR_RETRY_COUNT) {
      try {
        prepareThenPlay(current.getUrl(), current.isStream());
        return true;
      } catch (IOException e) {
        Log.e(LOG_TAG, "IOException on playlist entry " + current.getId(), e);
        errorCt++;
        if (errorCt >= ERROR_RETRY_COUNT) {
          Toast.makeText(getApplicationContext(),
              getResources().getString(R.string.msg_playback_error),
              Toast.LENGTH_LONG).show();
        }
      }
    }

    return false;
  }

  private void playNextEntry() {
    do {
      long id = current.getId();
      if (id != -1) {
        current = playlist.getNextEntry(current.getId());
      } else {
        current = playlist.getFirstUnreadEntry();
      }
    } while (current != null && !playCurrent(0));
  }

  private void playPreviousEntry() {
    do {
      current = playlist.getPreviousEntry(current.getId());
    } while (current != null && !playCurrent(0));
  }

  private void playFirstUnreadEntry() {
    do {
      current = playlist.getFirstUnreadEntry();
    } while (current != null && !playCurrent(0));

    if (current == null) {
      stopSelfResult(startId);
    }
  }

  private void finishEntryAndPlayNext() {
    do {
      current = playlist.getNextEntry(current.getId());
    } while (current != null && !playCurrent(0));

    if (current == null) {
      stopSelfResult(startId);
    }
  }

  synchronized private int getPosition() {
    if (isPrepared) {
      return mediaPlayer.getCurrentPosition();
    }
    return 0;
  }

  synchronized private boolean isPlaying() {
    return isPrepared && mediaPlayer.isPlaying();
  }

  synchronized private void seekRelative(int pos) {
    if (isPrepared) {
      mediaPlayer.seekTo(mediaPlayer.getCurrentPosition() + pos);
    }
  }

  synchronized private void seekTo(int pos) {
    if (isPrepared) {
      mediaPlayer.seekTo(pos);
    }
  }

  private void prepareThenPlay(String url, boolean stream)
      throws IllegalArgumentException, IllegalStateException, IOException {
    Log.d(LOG_TAG, "playNew");
    // First, clean up any existing audio.
    stop();

    if (isPlaylist(url)) {
      downloadPlaylist(url);
      if (playlistUrls.size() > 0) {
        url = playlistUrls.remove(0);
      } else {
        throw new IOException("Empty playlist downloaded");
      }
    }

    Log.d(LOG_TAG, "listening to " + url + " stream=" + stream);
    String playUrl = url;
    // From 2.2 on (SDK ver 8), the local mediaplayer can handle Shoutcast
    // streams natively. Let's detect that, and not proxy.
    int sdkVersion = 0;
    try {
      sdkVersion = Integer.parseInt(Build.VERSION.SDK);
    } catch (NumberFormatException ignored) {
    }

    if (stream && sdkVersion < 8) {
      if (proxy == null) {
        proxy = new StreamProxy();
        proxy.init();
        proxy.start();
      }
      playUrl = String.format("http://127.0.0.1:%d/%s",
          proxy.getPort(), url);
    }

    markedRead = false;
    synchronized (this) {
      Log.d(LOG_TAG, "reset: " + playUrl);
      mediaPlayer.reset();
      mediaPlayer.setDataSource(playUrl);
      mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
      Log.d(LOG_TAG, "Preparing: " + playUrl);
      mediaPlayer.prepareAsync();
      Log.d(LOG_TAG, "Waiting for prepare");
    }
  }

  synchronized private void play() {
    if (!isPrepared || current == null) {
      Log.e(LOG_TAG, "play - not prepared");
      return;
    }
    Log.d(LOG_TAG, "play " + current.getId());
    mediaPlayer.start();
    mediaPlayerHasStarted = true;

    int icon = R.drawable.stat_notify_musicplayer;
    CharSequence contentText = current.getTitle();
    long when = System.currentTimeMillis();
    Notification notification = new Notification(icon, contentText, when);
    notification.flags = Notification.FLAG_NO_CLEAR
        | Notification.FLAG_ONGOING_EVENT;
    Context c = getApplicationContext();
    CharSequence title = getString(R.string.app_name);
    Intent notificationIntent;
    if (current.getActivityData() != null) {
      notificationIntent = new Intent(this, current.getActivity());
      notificationIntent.putExtra("fixme", current.getActivityData());
      notificationIntent.putExtra("fixme2",
          R.string.msg_main_subactivity_nowplaying);
    } else {
      notificationIntent = new Intent(this, IceplayActivity.class);
    }
    notificationIntent.setAction(Intent.ACTION_VIEW);
    notificationIntent.addCategory(Intent.CATEGORY_DEFAULT);
    notificationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    PendingIntent contentIntent = PendingIntent.getActivity(c, 0,
        notificationIntent, PendingIntent.FLAG_CANCEL_CURRENT);
    notification.setLatestEventInfo(c, title, contentText, contentIntent);
    notificationManager.notify(NOTIFICATION_ID, notification);

    // Change broadcasts are sticky, so when a new receiver connects, it will
    // have the data without polling.
    if (lastChangeBroadcast != null) {
      getApplicationContext().removeStickyBroadcast(lastChangeBroadcast);
    }
    lastChangeBroadcast = new Intent(SERVICE_CHANGE_NAME);
    lastChangeBroadcast.putExtra(EXTRA_TITLE, current.getTitle());
    lastChangeBroadcast.putExtra(EXTRA_ID, current.getId());
    getApplicationContext().sendStickyBroadcast(lastChangeBroadcast);

//    if (current != null && current.getUrl() != null) {
//      Tracker.PlayEvent e = new Tracker.PlayEvent(current.getUrl());
//      Tracker.instance(getApplication()).trackLink(e);
//    }
  }

  synchronized private void pause() {
    Log.d(LOG_TAG, "pause");
    if (isPrepared) {
      mediaPlayer.pause();
    }
    notificationManager.cancel(NOTIFICATION_ID);

//    if (current != null) {
//      Tracker.PauseEvent e = new Tracker.PauseEvent(current.getUrl());
//      Tracker.instance(getApplication()).trackLink(e);
//    }
  }

  synchronized private void stop() {
    Log.d(LOG_TAG, "stop");
    if (isPrepared) {
      isPrepared = false;
      if (proxy != null) {
        proxy.stop();
        proxy = null;
      }
      mediaPlayer.stop();
    }
  }

  @Override
  public void onPrepared(MediaPlayer mp) {
    Log.d(LOG_TAG, "Prepared");
    synchronized (this) {
      if (mediaPlayer != null) {
        isPrepared = true;
      }
    }
    play();

    updateProgressThread = new Thread(new Runnable() {
      public void run() {
        // Initially, don't send any updates, since it takes a while for the
        // media player to settle down. 
        try {
          Thread.sleep(2000);
        } catch (InterruptedException e) {
          return;
        }
        while (true) {
          updateProgress();
          try {
            Thread.sleep(500);
          } catch (InterruptedException e) {
            break;
          }
        }
      }
    });
    updateProgressThread.start();
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    Log.w(LOG_TAG, "Service exiting");

    stop();

    if (updateProgressThread != null) {
      updateProgressThread.interrupt();
      try {
        updateProgressThread.join(3000);
      } catch (InterruptedException e) {
        Log.e(LOG_TAG, "", e);
      }
    }

    synchronized (this) {
      if (mediaPlayer != null) {
        if (mediaPlayerHasStarted) {
          mediaPlayer.release();
        }
        mediaPlayer = null;
      }
    }

    serviceLooper.quit();

    notificationManager.cancel(NOTIFICATION_ID);
    if (lastChangeBroadcast != null) {
      getApplicationContext().removeStickyBroadcast(lastChangeBroadcast);
    }
    getApplicationContext().sendBroadcast(new Intent(SERVICE_CLOSE_NAME));

    telephonyManager.listen(listener, PhoneStateListener.LISTEN_NONE);
  }

  
  public void onBufferingUpdate(MediaPlayer mp, int progress) {
    if (isPrepared) {
      lastBufferPercent = progress;
      updateProgress();
    }
  }

  /**
   * Sends an UPDATE broadcast with the latest info.
   */
  private void updateProgress() {
    if (lastUpdateBroadcast != null) {
      getApplicationContext().removeStickyBroadcast(lastUpdateBroadcast);
    }

    if (mediaPlayer != null && isPrepared) {

      int duration = mediaPlayer.getDuration(),
          position = mediaPlayer.getCurrentPosition();

      Intent tempUpdateBroadcast = new Intent(SERVICE_UPDATE_NAME);
      tempUpdateBroadcast.putExtra(EXTRA_DURATION, duration);
      tempUpdateBroadcast.putExtra(EXTRA_DOWNLOADED,
          (int) ((lastBufferPercent / 100.0) * duration));
      tempUpdateBroadcast.putExtra(EXTRA_POSITION, position);
      tempUpdateBroadcast.putExtra(EXTRA_IS_PLAYING, mediaPlayer.isPlaying());

      // Update broadcasts while playing are not sticky, due to concurrency
      // issues.  These fire very often, so this shouldn't be a problem.
      getApplicationContext().sendBroadcast(tempUpdateBroadcast);
    } else {
      lastUpdateBroadcast = new Intent(SERVICE_UPDATE_NAME);
      lastUpdateBroadcast.putExtra(EXTRA_IS_PLAYING, false);
      getApplicationContext().sendStickyBroadcast(lastUpdateBroadcast);
    }
  }

  @Override
  public void onCompletion(MediaPlayer mp) {
    Log.w(LOG_TAG, "onComplete()");

    synchronized (this) {
      if (!isPrepared) {
        // This file was not good and MediaPlayer quit
        Log.w(LOG_TAG,
            "MediaPlayer refused to play current item. Bailing on prepare.");
      }
    }

//    if (current != null) {
//      Tracker.StopEvent e = new Tracker.StopEvent(current.getUrl());
//      Tracker.instance(getApplication()).trackLink(e);
//    }

    // Unfinished playlist
    if (playlistUrls != null && playlistUrls.size() > 0) {
      boolean successfulPlay = false;
      while (!successfulPlay && playlistUrls.size() > 0) {
        String url = playlistUrls.remove(0);
        errorCt = 0;
        while (errorCt < ERROR_RETRY_COUNT) {
          try {
            prepareThenPlay(url, current.isStream());
            successfulPlay = true;
            break;
          } catch (IllegalArgumentException e) {
            Log.e(LOG_TAG, "", e);
            errorCt++;
          } catch (IllegalStateException e) {
            Log.e(LOG_TAG, "", e);
            errorCt++;
          } catch (IOException e) {
            Log.e(LOG_TAG, "", e);
            errorCt++;
          }
        }

        if (errorCt >= ERROR_RETRY_COUNT) {
          Toast.makeText(getApplicationContext(),
              getResources().getString(R.string.msg_playback_error),
              Toast.LENGTH_LONG).show();
        }
      }
    }

    if (currentAction.equals(SERVICE_PLAY_ENTRY)) {
      finishEntryAndPlayNext();
    } else {
      stopSelfResult(startId);
    }
  }

  @Override
  public boolean onError(MediaPlayer mp, int what, int extra) {
    Log.w(LOG_TAG, "onError(" + what + ", " + extra + ")");
    synchronized (this) {
      if (!isPrepared) {
        // This file was not good and MediaPlayer quit
        Log.w(LOG_TAG,
            "MediaPlayer refused to play current item. Bailing on prepare.");
      }
    }
    isPrepared = false;
    if (mediaPlayerHasStarted) {
      mediaPlayer.reset();
    }

    Log.e(LOG_TAG, "Media player onError, ct:" + errorCt);
    errorCt++;
    if (errorCt < ERROR_RETRY_COUNT) {
      playCurrent(errorCt);
      // Returning true means we handled the error, false causes the
      // onCompletion handler to be called
      return true;
    } else {
      return false;
    }
  }

  @Override
  public boolean onInfo(MediaPlayer arg0, int arg1, int arg2) {
    Log.w(LOG_TAG, "onInfo(" + arg1 + ", " + arg2 + ")");
    return false;
  }

  private boolean isPlaylist(String url) {
    return url.indexOf("m3u") > -1 || url.indexOf("pls") > -1;
  }

  private boolean downloadPlaylist(String url) throws IOException {
    Log.d(LOG_TAG, "downloading " + url);
    URLConnection cn = new URL(url).openConnection();
    cn.connect();
    InputStream stream = cn.getInputStream();
    if (stream == null) {
      Log.e(LOG_TAG, "Unable to create InputStream for url: + url");
      return false;
    }

    File downloadingMediaFile = new File(getCacheDir(), "playlist_data");
    FileOutputStream out = new FileOutputStream(downloadingMediaFile);
    byte buf[] = new byte[16384];
    int bytesRead;
    while ((bytesRead = stream.read(buf)) > 0) {
      out.write(buf, 0, bytesRead);
    }

    stream.close();
    out.close();
    PlaylistParser parser;
    if (url.indexOf("m3u") > -1) {
      parser = new M3uParser(downloadingMediaFile);
    } else if (url.indexOf("pls") > -1) {
      parser = new PlsParser(downloadingMediaFile);
    } else {
      return false;
    }
    playlistUrls = parser.getUrls();
    return true;
  }

}
