package com.emeka45.universalmp3player;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.ViewGroup;
import android.window.OnBackInvokedDispatcher;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String TAG = "UniversalMp3";
    private static final int AUDIO_PERMISSION = 2001;
    private static final String MEDIA_HOST = "universal.local";

    private WebView webView;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private MediaPlayer player;
    private long playingMediaId = -1;
    private File cachedAudioFile;
    private float volume = 1f;

    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private AudioManager.OnAudioFocusChangeListener focusListener;

    private android.webkit.ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(false);
        if (Build.VERSION.SDK_INT >= 21) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        WebView.setWebContentsDebuggingEnabled(true);

        webView.addJavascriptInterface(new NativeBridge(), "UniversalNative");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                WebResourceResponse response = serveMedia(request);
                return response != null ? response : super.shouldInterceptRequest(view, request);
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                Log.d(TAG, message.message() + " (" + message.sourceId() + ":" + message.lineNumber() + ")");
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView view, android.webkit.ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("audio/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                fileCallback = callback;
                startActivityForResult(intent, 1001);
                return true;
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
        registerBackHandler();
        requestAudioPermission();
    }

    /* ------------------------------------------------------------ permissions */

    private String audioPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_AUDIO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private boolean hasAudioPermission() {
        return checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAudioPermission() {
        if (hasAudioPermission()) return;
        if (Build.VERSION.SDK_INT <= 28) {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, AUDIO_PERMISSION);
        } else {
            requestPermissions(new String[]{audioPermission()}, AUDIO_PERMISSION);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == AUDIO_PERMISSION) {
            callJs("window.nativePermissionReady && window.nativePermissionReady();");
        }
    }

    /* --------------------------------------------------------- media serving */

    private WebResourceResponse serveMedia(WebResourceRequest request) {
        Uri uri = request.getUrl();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !MEDIA_HOST.equalsIgnoreCase(uri.getHost())) return null;
        String path = uri.getPath();
        if (path == null) return null;
        if (path.startsWith("/art/")) return serveArtwork(path.substring("/art/".length()));
        if (path.startsWith("/media/")) return serveAudio(path.substring("/media/".length()), request);
        return null;
    }

    private WebResourceResponse serveArtwork(String rawId) {
        if (Build.VERSION.SDK_INT < 29) return null;
        try {
            Uri mediaUri = audioUri(Long.parseLong(rawId));
            Bitmap bitmap = getContentResolver().loadThumbnail(mediaUri, new Size(320, 320), null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out);
            bitmap.recycle();
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "max-age=86400");
            return new WebResourceResponse("image/jpeg", null, 200, "OK", headers,
                    new ByteArrayInputStream(out.toByteArray()));
        } catch (Throwable t) {
            return null;
        }
    }

    private WebResourceResponse serveAudio(String rawId, WebResourceRequest request) {
        try {
            Uri mediaUri = audioUri(Long.parseLong(rawId));
            String mime = getContentResolver().getType(mediaUri);
            if (mime == null) mime = "audio/mpeg";
            final android.os.ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(mediaUri, "r");
            if (pfd == null) return null;
            long length = pfd.getStatSize();
            String range = request.getRequestHeaders().get("Range");
            long start = 0;
            long end = length > 0 ? length - 1 : -1;
            int status = 200;
            String reason = "OK";
            if (range != null && range.startsWith("bytes=") && length > 0) {
                String spec = range.substring(6).split(",")[0].trim();
                if (spec.startsWith("-")) {
                    start = Math.max(0, length - Long.parseLong(spec.substring(1)));
                } else {
                    String[] parts = spec.split("-");
                    start = Long.parseLong(parts[0]);
                    if (parts.length > 1 && !parts[1].isEmpty()) end = Math.min(length - 1, Long.parseLong(parts[1]));
                }
                if (start >= length) {
                    pfd.close();
                    Map<String, String> headers = new HashMap<>();
                    headers.put("Content-Range", "bytes */" + length);
                    return new WebResourceResponse(mime, null, 416, "Range Not Satisfiable", headers, null);
                }
                status = 206;
                reason = "Partial Content";
            }
            final long contentLength = end >= start ? end - start + 1 : length;
            FileInputStream stream = new FileInputStream(pfd.getFileDescriptor());
            if (start > 0) stream.skip(start);
            Map<String, String> headers = new HashMap<>();
            headers.put("Accept-Ranges", "bytes");
            headers.put("Content-Length", String.valueOf(contentLength));
            headers.put("Content-Range", "bytes " + start + "-" + end + "/" + length);
            return new WebResourceResponse(mime, null, status, reason, headers, new FilterInputStream(stream) {
                long remaining = contentLength;

                @Override
                public int read() throws IOException {
                    if (remaining <= 0) return -1;
                    int value = super.read();
                    if (value >= 0) remaining--;
                    return value;
                }

                @Override
                public int read(byte[] buffer, int offset, int len) throws IOException {
                    if (remaining <= 0) return -1;
                    int read = super.read(buffer, offset, (int) Math.min(len, remaining));
                    if (read > 0) remaining -= read;
                    return read;
                }

                @Override
                public void close() throws IOException {
                    super.close();
                    try { pfd.close(); } catch (Exception ignored) { }
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "Could not serve audio " + rawId, e);
            return null;
        }
    }

    private Uri audioCollection() {
        return Build.VERSION.SDK_INT >= 29
                ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
    }

    private Uri audioUri(long mediaId) {
        return ContentUris.withAppendedId(audioCollection(), mediaId);
    }

    /* ------------------------------------------------------------- playback */

    private void callJs(final String script) {
        if (webView == null) return;
        webView.post(() -> {
            if (webView != null) webView.evaluateJavascript(script, null);
        });
    }

    private void reportError(String message) {
        callJs("window.nativePlaybackError && window.nativePlaybackError(" + JSONObject.quote(message) + ");");
    }

    private boolean requestAudioFocus() {
        if (audioManager == null) return true;
        if (focusListener == null) {
            focusListener = change -> {
                if (change == AudioManager.AUDIOFOCUS_LOSS) {
                    pausePlayback();
                } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    pausePlayback();
                } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                    if (player != null) try { player.setVolume(volume * 0.2f, volume * 0.2f); } catch (Exception ignored) { }
                } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
                    if (player != null) try { player.setVolume(volume, volume); } catch (Exception ignored) { }
                }
            };
        }
        int result;
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .setUsage(AudioAttributes.USAGE_MEDIA).build())
                    .setOnAudioFocusChangeListener(focusListener)
                    .build();
            result = audioManager.requestAudioFocus(focusRequest);
        } else {
            result = audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN);
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
        } else if (focusListener != null) {
            audioManager.abandonAudioFocus(focusListener);
        }
    }

    private void pausePlayback() {
        if (player == null) return;
        try {
            if (player.isPlaying()) player.pause();
        } catch (Exception ignored) { }
        callJs("window.nativePlaybackPaused && window.nativePlaybackPaused();");
    }

    private void releasePlayer() {
        if (player != null) {
            try { player.reset(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
        deleteCachedAudio();
        playingMediaId = -1;
        abandonAudioFocus();
    }

    private void deleteCachedAudio() {
        if (cachedAudioFile != null) {
            try { cachedAudioFile.delete(); } catch (Exception ignored) { }
            cachedAudioFile = null;
        }
    }

    /**
     * Plays a MediaStore item. The content URI is handed to MediaPlayer directly;
     * if the device cannot open the provider stream, the file is copied into the
     * app cache and played from there.
     */
    private void startPlayback(final long mediaId, final boolean fromCache) {
        MediaPlayer created = new MediaPlayer();
        try {
            created.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build());
            created.setWakeMode(getApplicationContext(), android.os.PowerManager.PARTIAL_WAKE_LOCK);
            created.setOnPreparedListener(mp -> {
                requestAudioFocus();
                try { mp.setVolume(volume, volume); } catch (Exception ignored) { }
                mp.start();
                callJs("window.nativePlaybackReady && window.nativePlaybackReady();");
            });
            created.setOnCompletionListener(mp -> {
                playingMediaId = -1;
                callJs("window.nativePlaybackEnded && window.nativePlaybackEnded();");
            });
            created.setOnErrorListener((mp, what, extra) -> {
                Log.w(TAG, "MediaPlayer error " + what + "/" + extra + " (cache=" + fromCache + ")");
                try { mp.reset(); mp.release(); } catch (Exception ignored) { }
                if (player == mp) player = null;
                playingMediaId = -1;
                abandonAudioFocus();
                if (!fromCache) copyToCacheAndPlay(mediaId);
                else reportError("Android could not decode this audio file (" + what + ", " + extra + ")");
                return true;
            });

            if (fromCache) {
                if (cachedAudioFile == null) throw new FileNotFoundException("Cached audio file is missing");
                created.setDataSource(cachedAudioFile.getAbsolutePath());
            } else {
                created.setDataSource(getApplicationContext(), audioUri(mediaId));
            }
            player = created;
            playingMediaId = mediaId;
            created.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "Playback setup failed (cache=" + fromCache + ")", e);
            try { created.release(); } catch (Exception ignored) { }
            if (player == created) player = null;
            playingMediaId = -1;
            if (!fromCache) copyToCacheAndPlay(mediaId);
            else reportError("Could not play this song: " + e.getMessage());
        }
    }

    private void copyToCacheAndPlay(final long mediaId) {
        io.execute(() -> {
            File temp = null;
            try {
                temp = File.createTempFile("universal_mp3_", ".audio", getCacheDir());
                try (InputStream in = getContentResolver().openInputStream(audioUri(mediaId));
                     OutputStream out = new FileOutputStream(temp)) {
                    if (in == null) throw new FileNotFoundException("Cannot read this audio file");
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                }
                final File ready = temp;
                runOnUiThread(() -> {
                    deleteCachedAudio();
                    cachedAudioFile = ready;
                    startPlayback(mediaId, true);
                });
            } catch (Exception e) {
                if (temp != null) try { temp.delete(); } catch (Exception ignored) { }
                reportError("Could not read this audio file: " + e.getMessage());
            }
        });
    }

    /* --------------------------------------------------------------- bridge */

    public class NativeBridge {

        @JavascriptInterface
        public boolean hasAudioPermission() {
            return MainActivity.this.hasAudioPermission();
        }

        @JavascriptInterface
        public void requestAudioPermission() {
            runOnUiThread(MainActivity.this::requestAudioPermission);
        }

        @JavascriptInterface
        public String scanMusic() {
            JSONArray out = new JSONArray();
            if (!MainActivity.this.hasAudioPermission()) return out.toString();
            String[] projection = {
                    MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.YEAR
            };
            String selection = MediaStore.Audio.Media.IS_MUSIC + " != 0 OR " + MediaStore.Audio.Media.IS_PODCAST + " != 0";
            try (Cursor cursor = getContentResolver().query(audioCollection(), projection, selection, null,
                    MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")) {
                if (cursor == null) return out.toString();
                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
                int titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                int durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                int mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE);
                int yearCol = cursor.getColumnIndex(MediaStore.Audio.Media.YEAR);
                int pathCol = Build.VERSION.SDK_INT >= 29
                        ? cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                        : -1;
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idCol);
                    String name = cursor.isNull(nameCol) ? "" : cursor.getString(nameCol);
                    String title = cursor.isNull(titleCol) ? "" : cursor.getString(titleCol);
                    if (title.trim().isEmpty()) title = name.isEmpty() ? "Untitled" : name.replaceAll("\\.[^.]+$", "");
                    String artist = cursor.isNull(artistCol) ? "" : cursor.getString(artistCol);
                    String album = cursor.isNull(albumCol) ? "" : cursor.getString(albumCol);
                    String folder = pathCol >= 0 && !cursor.isNull(pathCol) ? cursor.getString(pathCol) : "Music";
                    try {
                        JSONObject song = new JSONObject();
                        song.put("id", "native:" + id);
                        song.put("mediaId", id);
                        song.put("name", name);
                        song.put("title", title);
                        song.put("artist", artist.trim().isEmpty() || "<unknown>".equals(artist) ? "Unknown artist" : artist);
                        song.put("album", album.trim().isEmpty() ? "Unknown album" : album);
                        song.put("genre", "Unknown");
                        song.put("year", yearCol >= 0 && !cursor.isNull(yearCol) ? cursor.getInt(yearCol) : "");
                        song.put("folder", folder);
                        song.put("duration", cursor.isNull(durationCol) ? 0 : cursor.getLong(durationCol) / 1000.0);
                        song.put("mime", cursor.isNull(mimeCol) ? "audio/mpeg" : cursor.getString(mimeCol));
                        song.put("url", "https://" + MEDIA_HOST + "/media/" + id);
                        song.put("cover", Build.VERSION.SDK_INT >= 29 ? "https://" + MEDIA_HOST + "/art/" + id : "");
                        song.put("native", true);
                        out.put(song);
                    } catch (Exception ignored) { }
                }
            } catch (Exception e) {
                Log.w(TAG, "MediaStore scan failed", e);
            }
            return out.toString();
        }

        @JavascriptInterface
        public void playMusic(final long mediaId) {
            runOnUiThread(() -> {
                releasePlayer();
                startPlayback(mediaId, false);
            });
        }

        @JavascriptInterface
        public void pauseMusic() {
            runOnUiThread(MainActivity.this::pausePlayback);
        }

        @JavascriptInterface
        public void resumeMusic() {
            runOnUiThread(() -> {
                if (player == null) return;
                try {
                    requestAudioFocus();
                    player.start();
                } catch (Exception e) {
                    reportError("Could not resume playback: " + e.getMessage());
                }
            });
        }

        @JavascriptInterface
        public void stopMusic() {
            runOnUiThread(MainActivity.this::releasePlayer);
        }

        @JavascriptInterface
        public void seekMusic(final int positionMs) {
            runOnUiThread(() -> {
                if (player == null) return;
                try { player.seekTo(Math.max(0, positionMs)); } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public void setVolume(final float value) {
            volume = Math.max(0f, Math.min(1f, value));
            runOnUiThread(() -> {
                if (player == null) return;
                try { player.setVolume(volume, volume); } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public boolean isNativePlaying() {
            try { return player != null && player.isPlaying(); } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public int nativePosition() {
            try { return player == null ? 0 : player.getCurrentPosition(); } catch (Exception e) { return 0; }
        }

        @JavascriptInterface
        public int nativeDuration() {
            try { return player == null ? 0 : player.getDuration(); } catch (Exception e) { return 0; }
        }

        @JavascriptInterface
        public long nativePlayingId() {
            return playingMediaId;
        }

        @JavascriptInterface
        public String searchCatalog(String query) {
            if (query == null || query.trim().isEmpty()) return "[]";
            HttpURLConnection connection = null;
            try {
                URL url = new URL("https://itunes.apple.com/search?term="
                        + URLEncoder.encode(query.trim(), "UTF-8") + "&entity=song&limit=30");
                connection = open(url);
                if (connection.getResponseCode() / 100 != 2) return "[]";
                JSONArray results = new JSONObject(readAll(connection.getInputStream())).optJSONArray("results");
                return results == null ? "[]" : results.toString();
            } catch (Exception e) {
                Log.w(TAG, "Catalogue search failed", e);
                return "[]";
            } finally {
                if (connection != null) connection.disconnect();
            }
        }

        @JavascriptInterface
        public String searchFreeMusic(String query) {
            JSONArray out = new JSONArray();
            if (query == null || query.trim().isEmpty()) return out.toString();
            try {
                String search = URLEncoder.encode("mediatype:audio AND " + query.trim(), "UTF-8");
                HttpURLConnection connection = open(new URL("https://archive.org/advancedsearch.php?q=" + search
                        + "&fl[]=identifier&fl[]=title&fl[]=creator&rows=12&output=json"));
                if (connection.getResponseCode() / 100 != 2) return out.toString();
                JSONObject response = new JSONObject(readAll(connection.getInputStream())).optJSONObject("response");
                connection.disconnect();
                JSONArray docs = response == null ? null : response.optJSONArray("docs");
                if (docs == null) return out.toString();
                for (int i = 0; i < docs.length() && out.length() < 8; i++) {
                    JSONObject doc = docs.getJSONObject(i);
                    String identifier = doc.optString("identifier");
                    if (identifier.isEmpty()) continue;
                    HttpURLConnection meta = null;
                    try {
                        meta = open(new URL("https://archive.org/metadata/" + URLEncoder.encode(identifier, "UTF-8")));
                        if (meta.getResponseCode() / 100 != 2) continue;
                        JSONArray files = new JSONObject(readAll(meta.getInputStream())).optJSONArray("files");
                        if (files == null) continue;
                        for (int k = 0; k < files.length(); k++) {
                            JSONObject file = files.getJSONObject(k);
                            String name = file.optString("name");
                            if (!name.toLowerCase(Locale.US).endsWith(".mp3") || file.optLong("size", 0) <= 0) continue;
                            JSONObject item = new JSONObject();
                            item.put("identifier", identifier);
                            item.put("title", doc.optString("title", identifier));
                            item.put("artist", doc.optString("creator", "Unknown artist"));
                            item.put("source", "Internet Archive");
                            item.put("downloadUrl", "https://archive.org/download/" + identifier + "/"
                                    + Uri.encode(name, "/"));
                            out.put(item);
                            break;
                        }
                    } catch (Exception ignored) {
                    } finally {
                        if (meta != null) meta.disconnect();
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Free music search failed", e);
            }
            return out.toString();
        }

        @JavascriptInterface
        public void downloadMusic(final String url, final String title, final String mime) {
            if (url == null || !url.startsWith("https://archive.org/")) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Downloads are limited to supported legal music sources.", Toast.LENGTH_SHORT).show());
                callJs("window.nativeDownloadFailed && window.nativeDownloadFailed(\"Unsupported download source.\");");
                return;
            }
            io.execute(() -> {
                Uri collection = Build.VERSION.SDK_INT >= 29
                        ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                String filename = safeFilename(title);
                ContentValues values = new ContentValues();
                values.put(MediaStore.Audio.Media.DISPLAY_NAME, filename);
                values.put(MediaStore.Audio.Media.TITLE, title == null ? filename : title);
                values.put(MediaStore.Audio.Media.IS_MUSIC, 1);
                values.put(MediaStore.Audio.Media.MIME_TYPE, mime == null || mime.isEmpty() ? "audio/mpeg" : mime);
                if (Build.VERSION.SDK_INT >= 29) {
                    values.put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Universal MP3 Downloads");
                    values.put(MediaStore.Audio.Media.IS_PENDING, 1);
                }
                Uri target = null;
                HttpURLConnection connection = null;
                try {
                    target = getContentResolver().insert(collection, values);
                    if (target == null) throw new IOException("Could not create the destination file");
                    connection = open(new URL(url));
                    connection.setInstanceFollowRedirects(true);
                    if (connection.getResponseCode() / 100 != 2) throw new IOException("HTTP " + connection.getResponseCode());
                    try (InputStream in = connection.getInputStream();
                         OutputStream out = getContentResolver().openOutputStream(target)) {
                        if (out == null) throw new IOException("Cannot write to the music folder");
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                    }
                    if (Build.VERSION.SDK_INT >= 29) {
                        ContentValues done = new ContentValues();
                        done.put(MediaStore.Audio.Media.IS_PENDING, 0);
                        getContentResolver().update(target, done, null, null);
                    }
                    final Uri saved = target;
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "Saved to Music/Universal MP3 Downloads", Toast.LENGTH_LONG).show());
                    callJs("window.nativeDownloadComplete && window.nativeDownloadComplete("
                            + JSONObject.quote(filename) + "," + JSONObject.quote(saved.toString()) + ");");
                } catch (Exception e) {
                    if (target != null) try { getContentResolver().delete(target, null, null); } catch (Exception ignored) { }
                    final String message = e.getMessage() == null ? "unknown error" : e.getMessage();
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "Download failed: " + message, Toast.LENGTH_LONG).show());
                    callJs("window.nativeDownloadFailed && window.nativeDownloadFailed("
                            + JSONObject.quote("Download failed: " + message) + ");");
                } finally {
                    if (connection != null) connection.disconnect();
                }
            });
        }

        private HttpURLConnection open(URL url) throws IOException {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "Universal-MP3-Player/1.0");
            return connection;
        }

        private String readAll(InputStream in) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            try (InputStream stream = in) {
                while ((read = stream.read(chunk)) != -1) buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private String safeFilename(String value) {
        String name = value == null || value.trim().isEmpty() ? "music" : value.trim();
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (name.length() > 80) name = name.substring(0, 80);
        if (!name.toLowerCase(Locale.US).endsWith(".mp3")) name = name + ".mp3";
        return name;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != 1001 || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                result = new Uri[count];
                for (int i = 0; i < count; i++) result[i] = data.getClipData().getItemAt(i).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    @Override
    protected void onDestroy() {
        releasePlayer();
        io.shutdownNow();
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private void goBack() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else finish();
    }

    @SuppressWarnings("deprecation")
    @SuppressLint({"MissingSuperCall", "GestureBackNavigation"})
    @Override
    public void onBackPressed() {
        goBack();
    }

    private void registerBackHandler() {
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goBack);
        }
    }
}
