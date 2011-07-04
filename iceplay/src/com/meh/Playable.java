// Copyright 2011 NPR
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.meh;

import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

/**
 * Represents a stream
 */
public class Playable implements Parcelable {
  private static final String LOG_TAG = Playable.class.getName();

  private Playable(long id, String url, String title, boolean isStream,
                  Class<?> activity, String activityData) {
    setId(id);
    setUrl(url);
    setTitle(title);
    setIsStream(isStream);
    setActivity(activity);
    setActivityData(activityData);
  }
  
  public Playable(String url, String title, Class<?> activity)
  {
      this(-1, url, title, true, activity, "");
  }
  
  public Playable(PlaylistEntry entry, Class<?> activity)
  {
      this(entry.url, entry.title, activity);
  }

  private long id;
  private String url;
  private String title;
  private boolean isStream;
  private Class<?> activity;
  private String activityData;

  public static final String PLAYABLE_TYPE = "PLAYABLE_TYPE";

  public long getId() {
    return id;
  }

  public void setId(long id) {
    this.id = id;
  }

  public String getUrl() {
    return url;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public String getTitle() {
    return title;
  }

  public void setTitle(String title) {
    this.title = title;
  }

  public boolean isStream() {
    return isStream;
  }

  public void setIsStream(boolean stream) {
    isStream = stream;
  }

  public Class<?> getActivity() {
    return activity;
  }

  public void setActivity(Class<?> activity) {
    this.activity = activity;
  }

  public String getActivityData() {
    return activityData;
  }

  public void setActivityData(String activityData) {
    this.activityData = activityData;
  }

  
  public int describeContents() {
    return 0;
  }

  
  public void writeToParcel(Parcel out, int flags) {
    out.writeLong(id);
    out.writeString(url);
    out.writeString(title);
    out.writeString(Boolean.toString(isStream));
    out.writeString(activity.getName());
    out.writeString(activityData);
  }

  public static final Parcelable.Creator<Playable> CREATOR
      = new Parcelable.Creator<Playable>() {
    public Playable createFromParcel(Parcel in) {
      return new Playable(in);
    }

    public Playable[] newArray(int size) {
      return new Playable[size];
    }
  };

  private Playable(Parcel in) {
    id = in.readLong();
    url = in.readString();
    title = in.readString();
    isStream = Boolean.parseBoolean(in.readString());
    try {
      activity = Class.forName(in.readString());
    } catch (ClassNotFoundException e) {
      Log.e(LOG_TAG, "Class not found");
    }
    activityData = in.readString();
  }
//
//  public static class PlayableFactory {
//    public static Playable fromPlaylistEntry(PlaylistEntry playlistEntry) {
//      return new Playable(playlistEntry.id, playlistEntry.url,
//          playlistEntry.title, playlistEntry.isStream,
//          NewsStoryActivity.class, playlistEntry.storyID);
//    }
//
//    public static Playable fromPodcastItem(Podcast.Item item, String url,
//                                           String title) {
//      return new Playable(-1, item.getUrl(), item.getTitle(), true,
//          PodcastActivity.class, url + ' ' + title);
//    }
//
//    public static Playable fromStationStream(String stationId,
//                                             Station.AudioStream stream) {
//      return new Playable(-1, stream.getUrl(), stream.getTitle(), true,
//          StationDetailsActivity.class, stationId);
//    }
//  }
}
