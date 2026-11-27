package com.logicrealm.rivune;

import android.app.Activity;
import android.content.ComponentName;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

final class NativePlaybackBridge {
    private static final String CONTRACT = "rivune.playback";
    private static final int CONTRACT_VERSION = 1;
    private static final String TRUSTED_HOST = "rivune.page.gd";

    private final Activity activity;
    private final WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Executor mainExecutor = command -> mainHandler.post(command);
    private final Map<String, JSONObject> library = new LinkedHashMap<>();
    private final List<String> pendingMessages = new ArrayList<>();

    private MediaController controller;
    private ListenableFuture<MediaController> controllerFuture;
    private boolean pageReady;
    private boolean nativeModeEnabled;
    private boolean released;
    private String lastSeekDiagnosticTrackId;

    private final Player.Listener playerListener = new Player.Listener() {
        @Override
        public void onEvents(Player player, Player.Events events) {
            publishState();
            scheduleProgressUpdate();
            diagnoseSeekbar(player);
        }

        @Override
        public void onPlaybackStateChanged(int playbackState) {
            String stateName;
            switch (playbackState) {
                case Player.STATE_IDLE: stateName = "IDLE"; break;
                case Player.STATE_BUFFERING: stateName = "BUFFERING"; break;
                case Player.STATE_READY: stateName = "READY"; break;
                case Player.STATE_ENDED: stateName = "ENDED"; break;
                default: stateName = "UNKNOWN"; break;
            }
            Log.i("RivunePlayback", "Media3 playback state: " + stateName);
        }

        @Override
        public void onPlayerError(androidx.media3.common.PlaybackException error) {
            StringBuilder details = new StringBuilder()
                    .append(error.getErrorCodeName())
                    .append(" (")
                    .append(error.errorCode)
                    .append(")");
            if (error.getMessage() != null) {
                details.append(": ").append(error.getMessage());
            }
            Throwable cause = error.getCause();
            if (cause != null) {
                details.append(" | Cause: ")
                        .append(cause.getClass().getSimpleName());
                if (cause.getMessage() != null) {
                    details.append(": ").append(cause.getMessage());
                }
            }
            String message = details.toString();
            Log.e("RivunePlayback", message, error);
            publishError(message);
            activity.runOnUiThread(() -> Toast.makeText(
                    activity, "RIVUNE playback error: " + message,
                    Toast.LENGTH_LONG
            ).show());
        }
    };

    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (released || controller == null || !nativeModeEnabled) return;
            publishState();
            if (controller.isPlaying()) {
                mainHandler.postDelayed(this, 750);
            }
        }
    };

    NativePlaybackBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    @JavascriptInterface
    public void postMessage(String message) {
        if (message == null || message.length() > 512 * 1024 || released) return;
        mainHandler.post(() -> receiveMessage(message));
    }

    void onPageStarted(String url) {
        pageReady = false;
        // A full navigation creates a fresh JavaScript context. Re-enable native
        // ownership only after that trusted page has finished loading.
        nativeModeEnabled = false;
    }

    void onPageFinished(String url) {
        if (!isTrustedPage(url) || released) return;
        pageReady = true;
        connectToPlaybackService();
        activateNativeModeIfReady();
    }

    void release() {
        released = true;
        pageReady = false;
        nativeModeEnabled = false;
        mainHandler.removeCallbacks(progressRunnable);
        if (controller != null) {
            controller.removeListener(playerListener);
            controller.release();
            controller = null;
        }
        if (controllerFuture != null) {
            MediaController.releaseFuture(controllerFuture);
            controllerFuture = null;
        }
        pendingMessages.clear();
    }

    private void connectToPlaybackService() {
        if (controller != null || controllerFuture != null || released) return;

        String userAgent = webView.getSettings().getUserAgentString();
        String cookie = CookieManager.getInstance().getCookie("https://rivune.page.gd/");
        PlaybackService.setWebRequestContext(userAgent, cookie);

        SessionToken token = new SessionToken(
                activity,
                new ComponentName(activity, PlaybackService.class)
        );
        final ListenableFuture<MediaController> future =
                new MediaController.Builder(activity, token).buildAsync();
        controllerFuture = future;
        future.addListener(() -> {
            if (released) {
                MediaController.releaseFuture(future);
                return;
            }
            try {
                controller = future.get();
                controllerFuture = null;
                controller.addListener(playerListener);
                activateNativeModeIfReady();
            } catch (Exception error) {
                controllerFuture = null;
                publishError("Could not connect to the native playback service");
            }
        }, mainExecutor);
    }

    private void activateNativeModeIfReady() {
        if (!pageReady || controller == null || nativeModeEnabled || released) return;

        webView.evaluateJavascript(
                "window.RivunePlayback && window.RivunePlayback.setNativeMode(true)",
                value -> {
                    if (released) return;
                    nativeModeEnabled = "true".equals(value);
                    if (!nativeModeEnabled) {
                        // The deployed page may not yet include the bridge contract.
                        // In that case leave its existing HTML audio player untouched.
                        pendingMessages.clear();
                        return;
                    }
                    List<String> messages = new ArrayList<>(pendingMessages);
                    pendingMessages.clear();
                    for (String message : messages) receiveMessage(message);
                    publishState();
                }
        );
    }

    private void receiveMessage(String rawMessage) {
        if (released) return;
        if (controller == null || !nativeModeEnabled) {
            if (pendingMessages.size() < 100) pendingMessages.add(rawMessage);
            return;
        }

        try {
            JSONObject envelope = new JSONObject(rawMessage);
            if (!CONTRACT.equals(envelope.optString("contract"))
                    || envelope.optInt("version", -1) != CONTRACT_VERSION) {
                return;
            }

            String type = envelope.optString("type", "");
            JSONObject payload = envelope.optJSONObject("payload");
            if (payload == null) payload = new JSONObject();

            switch (type) {
                case "webReady":
                    readLibrary(payload);
                    if (controller.getMediaItemCount() == 0) {
                        replaceQueueFromSnapshot(payload, false);
                    }
                    publishState();
                    break;
                case "libraryChanged":
                    readLibrary(payload);
                    break;
                case "queueChanged":
                    readLibrary(payload);
                    replaceQueueFromSnapshot(payload, controller.isPlaying());
                    publishState();
                    break;
                case "playTrack":
                    playTrack(payload);
                    break;
                case "play":
                    controller.play();
                    break;
                case "pause":
                    controller.pause();
                    break;
                case "next":
                    controller.seekToNextMediaItem();
                    break;
                case "previous":
                    controller.seekToPreviousMediaItem();
                    break;
                case "seek":
                    controller.seekTo(Math.max(0L, payload.optLong("positionMs", 0L)));
                    break;
                case "setVolume":
                    controller.setVolume(Math.max(0f, Math.min(1f,
                            (float) payload.optDouble("volume", 1.0))));
                    publishState();
                    break;
                case "shuffleChanged":
                    controller.setShuffleModeEnabled(payload.optBoolean("enabled", false));
                    publishState();
                    break;
                default:
                    // Forward-compatible: ignore message types not defined by this contract.
                    break;
            }
        } catch (JSONException | IllegalArgumentException error) {
            publishError("RIVUNE sent an invalid playback message");
        }
    }

    private void readLibrary(JSONObject payload) throws JSONException {
        JSONArray tracks = payload.optJSONArray("tracks");
        if (tracks == null) return;
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject track = tracks.optJSONObject(i);
            if (track == null) continue;
            String id = trackId(track);
            if (id != null) library.put(id, track);
        }
    }

    private void playTrack(JSONObject payload) throws JSONException {
        JSONObject selectedTrack = payload.optJSONObject("track");
        if (selectedTrack == null) return;

        String selectedId = trackId(selectedTrack);
        if (selectedId == null) return;
        library.put(selectedId, selectedTrack);

        JSONArray queueIds = payload.optJSONArray("queueTrackIds");
        List<MediaItem> items = buildQueue(queueIds);
        MediaItem selectedItem = buildMediaItem(selectedTrack);
        if (selectedItem == null) {
            publishError("Android could not create a playable audio URL for this track");
            return;
        }
        int index = payload.optInt("currentIndex", 0);

        if (items.isEmpty()) {
            items.add(selectedItem);
            index = 0;
        } else {
            int selectedIndex = findMediaItem(items, selectedId);
            if (selectedIndex < 0) {
                items.add(selectedItem);
                selectedIndex = items.size() - 1;
            }
            if (index < 0 || index >= items.size()
                    || !selectedId.equals(items.get(index).mediaId)) {
                index = selectedIndex;
            }
        }

        controller.setMediaItems(items, index, 0L);
        controller.prepare();
        controller.play();
        publishState();
    }

    private void replaceQueueFromSnapshot(JSONObject payload, boolean resume) {
        JSONArray queueIds = payload.optJSONArray("queueTrackIds");
        if (queueIds == null) return;

        String currentId = controller.getCurrentMediaItem() == null
                ? null : controller.getCurrentMediaItem().mediaId;
        long position = controller.getCurrentPosition();
        List<MediaItem> items = buildQueue(queueIds);
        if (items.isEmpty()) return;

        int index = currentId == null ? payload.optInt("currentIndex", 0)
                : findMediaItem(items, currentId);
        if (index < 0 || index >= items.size()) {
            index = Math.max(0, Math.min(items.size() - 1,
                    payload.optInt("currentIndex", 0)));
        }

        controller.setMediaItems(items, index, position);
        controller.setShuffleModeEnabled(payload.optBoolean(
                "shuffleEnabled", controller.getShuffleModeEnabled()));
        if (payload.has("volume")) {
            controller.setVolume(Math.max(0f, Math.min(1f,
                    (float) payload.optDouble("volume", controller.getVolume()))));
        }
        controller.prepare();
        if (resume) controller.play();
    }

    private List<MediaItem> buildQueue(JSONArray queueIds) {
        List<MediaItem> items = new ArrayList<>();
        if (queueIds == null) return items;

        for (int i = 0; i < queueIds.length(); i++) {
            String id = queueIds.optString(i, "");
            JSONObject track = library.get(id);
            if (track == null) continue;
            MediaItem item = buildMediaItem(track);
            if (item != null) items.add(item);
        }
        return items;
    }

    private MediaItem buildMediaItem(JSONObject track) {
        try {
            String id = trackId(track);
            String rawStreamUrl = track.optString("stream_url",
                    track.optString("streamUrl", ""));
            String streamUrl = resolveTrustedStreamUrl(rawStreamUrl);
            if (id == null || streamUrl == null) {
                Log.e("RivunePlayback", "Rejected stream URL. Track ID valid: "
                        + (id != null) + ", URL present: "
                        + (rawStreamUrl != null && !rawStreamUrl.trim().isEmpty())
                        + ", page: " + webView.getUrl());
                return null;
            }

            MediaMetadata metadata = new MediaMetadata.Builder()
                    .setTitle(track.optString("title", "RIVUNE track"))
                    .setArtist(track.optString("artist", ""))
                    .setAlbumTitle(track.optString("album", ""))
                    .build();

            return new MediaItem.Builder()
                    .setMediaId(id)
                    .setUri(Uri.parse(streamUrl))
                    // RIVUNE's library is MP3; stream.php uses a dynamic URL with no file extension.
                    .setMimeType(MimeTypes.AUDIO_MPEG)
                    .setMediaMetadata(metadata)
                    .build();
        } catch (RuntimeException error) {
            Log.e("RivunePlayback", "Could not build Media3 media item", error);
            return null;
        }
    }

    private String resolveTrustedStreamUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) return null;
        try {
            String currentPage = webView.getUrl();
            if (!isTrustedPage(currentPage)) return null;
            URI base = new URI(currentPage);
            URI resolved = base.resolve(new URI(rawUrl.trim())).normalize();
            if (!"https".equalsIgnoreCase(resolved.getScheme())
                    || !TRUSTED_HOST.equalsIgnoreCase(resolved.getHost())
                    || resolved.getUserInfo() != null
                    || (resolved.getPort() != -1 && resolved.getPort() != 443)
                    || resolved.getPath() == null
                    || !resolved.getPath().startsWith("/")) {
                return null;
            }
            return resolved.toASCIIString();
        } catch (Exception error) {
            return null;
        }
    }

    private boolean isTrustedPage(String url) {
        if (url == null) return false;
        try {
            URI uri = new URI(url);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && TRUSTED_HOST.equalsIgnoreCase(uri.getHost())
                    && uri.getUserInfo() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (Exception error) {
            return false;
        }
    }

    private String trackId(JSONObject track) {
        Object value = track.opt("id");
        if (value == null || value == JSONObject.NULL) return null;
        String id = String.valueOf(value);
        return id.matches("[A-Za-z0-9_-]{1,128}") ? id : null;
    }

    private Object jsonTrackId(String id) {
        if (id != null && id.matches("[0-9]{1,18}")) {
            try {
                return Long.parseLong(id);
            } catch (NumberFormatException ignored) {
                // Fall through and preserve the identifier as a string.
            }
        }
        return id;
    }

    private int findMediaItem(List<MediaItem> items, String id) {
        for (int i = 0; i < items.size(); i++) {
            if (id.equals(items.get(i).mediaId)) return i;
        }
        return -1;
    }

    private void diagnoseSeekbar(Player player) {
        if (player.getPlaybackState() != Player.STATE_READY
                || player.getCurrentMediaItem() == null) {
            return;
        }

        String id = player.getCurrentMediaItem().mediaId;
        if (id == null || id.equals(lastSeekDiagnosticTrackId)) return;
        lastSeekDiagnosticTrackId = id;

        long duration = player.getDuration();
        boolean knownDuration = duration != C.TIME_UNSET && duration > 0;
        boolean seekable = player.isCurrentMediaItemSeekable();
        if (knownDuration && seekable) return;

        String reason = !knownDuration && !seekable
                ? "duration unavailable and stream not seekable"
                : (!knownDuration ? "duration unavailable" : "stream not seekable");
        Log.w("RivunePlayback", "Track " + id + " has no notification seek bar: " + reason);
        activity.runOnUiThread(() -> Toast.makeText(
                activity,
                "RIVUNE: This track has no seek bar (" + reason + ")",
                Toast.LENGTH_LONG
        ).show());
    }

    private void scheduleProgressUpdate() {
        mainHandler.removeCallbacks(progressRunnable);
        if (controller != null && controller.isPlaying() && nativeModeEnabled) {
            mainHandler.postDelayed(progressRunnable, 750);
        }
    }

    private void publishState() {
        if (controller == null || !pageReady || !nativeModeEnabled || released) return;
        try {
            JSONArray queueIds = new JSONArray();
            for (int i = 0; i < controller.getMediaItemCount(); i++) {
                String id = controller.getMediaItemAt(i).mediaId;
                queueIds.put(jsonTrackId(id));
            }

            String trackId = controller.getCurrentMediaItem() == null
                    ? null : controller.getCurrentMediaItem().mediaId;
            long duration = controller.getDuration();
            if (duration == C.TIME_UNSET || duration < 0) duration = 0;

            JSONObject state = new JSONObject();
            state.put("trackId", trackId == null ? JSONObject.NULL : jsonTrackId(trackId));
            state.put("queueTrackIds", queueIds);
            state.put("currentIndex", controller.getCurrentMediaItemIndex());
            state.put("isPlaying", controller.isPlaying());
            state.put("positionMs", Math.max(0L, controller.getCurrentPosition()));
            state.put("durationMs", duration);
            state.put("shuffleEnabled", controller.getShuffleModeEnabled());
            state.put("volume", controller.getVolume());

            webView.post(() -> webView.evaluateJavascript(
                    "window.RivunePlayback && window.RivunePlayback.onNativeStateChanged("
                            + state.toString() + ")",
                    null
            ));
        } catch (Exception ignored) {
            // A stale page or malformed transient state must not interrupt audio playback.
        }
    }

    private void publishError(String message) {
        if (!pageReady || released) return;
        String safeMessage = JSONObject.quote(message == null ? "Playback error" : message);
        webView.post(() -> webView.evaluateJavascript(
                "window.RivunePlayback && window.RivunePlayback.onNativeError("
                        + safeMessage + ")",
                null
        ));
    }
}
