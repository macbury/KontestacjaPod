package macbury.enklawa.services;

import android.app.Service;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import com.google.gson.reflect.TypeToken;
import com.koushikdutta.async.future.FutureCallback;
import com.koushikdutta.ion.Ion;
import com.koushikdutta.ion.ProgressCallback;

import java.util.ArrayList;

import macbury.enklawa.api.APIEpisode;
import macbury.enklawa.api.APIProgram;
import macbury.enklawa.api.APIResponse;
import macbury.enklawa.api.APIThread;
import macbury.enklawa.db.DatabaseCRUDListener;
import macbury.enklawa.db.models.Episode;
import macbury.enklawa.db.models.ForumThread;
import macbury.enklawa.db.models.Program;
import macbury.enklawa.db.scopes.EpisodesScope;
import macbury.enklawa.db.scopes.ProgramsScope;
import macbury.enklawa.db.scopes.ThreadScope;
import macbury.enklawa.managers.Enklawa;

public class SyncPodService extends Service implements FutureCallback<APIResponse> {
  private static final String TAG = "SyncPodService";
  private static final String WAKE_LOCK_TAG = "SyncPodWakeLock";
  private static final int SYNC_POD_NOTIFICATION_ID = 666;
  private Enklawa app;
  private PowerManager powerManager;
  private PowerManager.WakeLock wakeLock;

  private static boolean running = false;

  public SyncPodService() {
  }

  public static boolean isRunning() {
    return running;
  }

  @Override
  public IBinder onBind(Intent intent) {
    throw new UnsupportedOperationException("Not yet implemented");
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    Log.d(TAG, "Destroy");
    wakeLock.release();
    Ion.getDefault(this).cancelAll(this);
    running = false;
    app.broadcasts.podSync();
  }

  @Override
  public void onCreate() {
    super.onCreate();
    Log.d(TAG, "Create");
    running           = true;
    this.app          = (Enklawa)getApplication();
    this.powerManager = (PowerManager) getSystemService(POWER_SERVICE);
    this.wakeLock     = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG);
    this.wakeLock.acquire();
    app.broadcasts.podSync();
    syncPod();
  }


  private void syncPod() {
    startForeground(SYNC_POD_NOTIFICATION_ID, this.app.notifications.syncPod());
    Log.i(TAG, "Syncing pod: "+app.settings.getApiEndpoint());
    Ion.with(this).load(app.settings.getApiEndpoint()).noCache().as(new TypeToken<APIResponse>() {}).setCallback(this);
  }

  @Override
  public void onCompleted(Exception e, APIResponse result) {
    if (e == null) {
      Log.d(TAG, "Downloaded pod data!");
      Enklawa.current().settings.setRadioURI(result.radio);
      SyncApiResponseWithDB syncDB = new SyncApiResponseWithDB();
      syncDB.execute(result);
    } else {
      Log.e(TAG, "Error while downloading pod data!");
      e.printStackTrace();
      showErrorSyncNotification(e);
      stopSelf();
    }
  }

  private void showErrorSyncNotification(Exception e) {
    app.notifications.manager.notify(1, app.notifications.syncPodError(e));
  }

  private void showNotificationsForNewEpisodesOrDownloadThem(ArrayList<Episode> newEpisodes) {
    for(Episode episode : newEpisodes) {
      if (episode.program.isFavorite()) {
        app.db.episodeFiles.createFromEpisode(episode);// TODO check if can do
        Log.i(TAG, "Show notification!!!!");
      }
      Log.i(TAG, "New episode: " + episode.name);
    }
    // else show summary
    // start download service
    app.services.downloadPendingEpisodes();
  }

  private class SyncApiResponseWithDB extends AsyncTask<APIResponse, Integer, Boolean> implements DatabaseCRUDListener<Episode> {
    private ArrayList<Episode> newEpisodes;


    private void syncProgramsAndEpisodes(ArrayList<APIProgram> resultPrograms) {
      ProgramsScope programs = app.db.programs;
      EpisodesScope episodes = app.db.episodes;
      episodes.addListener(this);
      for (APIProgram apiProgram : resultPrograms) {
        if (programs.updateFromApi(apiProgram)) {
          Program program = programs.find(apiProgram);
          Log.v(TAG, "Program: " +program.name);
          for(Episode episode : episodes.findEpisodesByApiProgramAndReturnNew(program, apiProgram.episodes)) {
            episode.program = program;
            if (episodes.update(episode)) {
              Log.v(TAG, "Episode: " + episode.name);
            } else {
              Log.e(TAG, "Could not save episode: " + episode.name);
            }
          }
        } else {
          Log.e(TAG, "Could not save program: " + apiProgram.name);
        }
      }

      episodes.removeListener(this);
    }

    private void syncThreads(ArrayList<APIThread> resultThreads) {
      ThreadScope threads = app.db.threads;
      for (APIThread apiThread : resultThreads) {
        ForumThread thread = threads.find(apiThread);
        if (thread == null) {
          thread = threads.buildFromApi(apiThread);
          threads.update(thread);
        }
      }
    }

    @Override
    protected Boolean doInBackground(APIResponse... params) {
      newEpisodes            = new ArrayList<Episode>();
      APIResponse result     = params[0];
      //syncThreads(result.forum);
      syncProgramsAndEpisodes(result.programs);

      return true;
    }

    @Override
    protected void onPostExecute(Boolean aBoolean) {
      super.onPostExecute(aBoolean);
      SyncPodService.this.showNotificationsForNewEpisodesOrDownloadThem(newEpisodes);
      stopSelf();
    }

    @Override
    public void afterCreate(Episode episode) {
      if (episode.isFresh()) {
        newEpisodes.add(episode);
      }
    }

    @Override
    public void afterDestroy(Episode object) {

    }

    @Override
    public void afterSave(Episode object) {

    }
  }

}
