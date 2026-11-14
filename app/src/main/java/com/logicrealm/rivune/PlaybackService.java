package com.logicrealm.rivune;

import android.app.PendingIntent;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.content.Intent;
import android.webkit.CookieManager;

import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import java.util.HashMap;
import java.util.Map;

import androidx.media3.common.AudioAttributes;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

public final class PlaybackService extends MediaSessionService {
    private static final String PLAYBACK_CHANNEL_ID = "rivune_media_playback_v2";

    private static volatile String webUserAgent;
    private static volatile String webCookie;

    static void setWebRequestContext(String userAgent, String cookie) {
        webUserAgent = userAgent;
        webCookie = cookie;
    }

    private ExoPlayer player;
    private MediaSession mediaSession;

    @Override
    public void onCreate() {
        super.onCreate();

        // Use a new channel ID so Android does not carry forward the old
        // silent channel settings created by Media3 on earlier app versions.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager =
                    getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(
                    PLAYBACK_CHANNEL_ID,
                    getString(R.string.rivune_playback_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("Playback controls and current track information");
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);
            notificationManager.createNotificationChannel(channel);
        }

        setMediaNotificationProvider(
                new DefaultMediaNotificationProvider.Builder(this)
                        .setChannelId(PLAYBACK_CHANNEL_ID)
                        .setChannelName(R.string.rivune_playback_channel)
                        .build()
        );

        Map<String, String> requestHeaders = new HashMap<>();
        requestHeaders.put("Referer", "https://rivune.page.gd/");
        requestHeaders.put("Origin", "https://rivune.page.gd");
        if (webCookie != null && !webCookie.trim().isEmpty()) {
            requestHeaders.put("Cookie", webCookie);
        }

        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent(webUserAgent == null || webUserAgent.trim().isEmpty()
                        ? "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 "
                                + "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                        : webUserAgent)
                .setDefaultRequestProperties(requestHeaders);
        DefaultDataSource.Factory dataSourceFactory =
                new DefaultDataSource.Factory(this, httpFactory);
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory))
                .build();
        // Media3 manages audio focus and interruption behaviour for the player.
        player.setAudioAttributes(AudioAttributes.DEFAULT, true);
        PendingIntent sessionActivity = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        mediaSession = new MediaSession.Builder(this, player)
                .setId("rivune-playback")
                .setSessionActivity(sessionActivity)
                .build();
    }

    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    @Override
    public void onDestroy() {
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
