package com.emeka45.universalmp3player;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
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

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private WebView webView;
    private static final int AUDIO_PERMISSION = 2001;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        webView.addJavascriptInterface(new NativeBridge(), "UniversalNative");
        webView.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("content".equalsIgnoreCase(uri.getScheme()) && "media".equalsIgnoreCase(uri.getAuthority())) {
                    try {
                        InputStream stream = getContentResolver().openInputStream(uri);
                        if (stream != null) {
                            String mime = getContentResolver().getType(uri);
                            return new WebResourceResponse(mime == null ? "audio/mpeg" : mime, null, stream);
                        }
                    } catch (Exception ignored) {}
                }
                return super.shouldInterceptRequest(view, request);
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, android.webkit.ValueCallback<Uri[]> callback, FileChooserParams params) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("audio/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                fileCallback = callback;
                startActivityForResult(i, 1001);
                return true;
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
        requestAudioPermission();
    }

    private void requestAudioPermission() {
        String permission = Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{permission}, AUDIO_PERMISSION);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == AUDIO_PERMISSION && webView != null) {
            webView.post(() -> webView.evaluateJavascript("window.nativePermissionReady && window.nativePermissionReady();", null));
        }
    }

    public class NativeBridge {
        @JavascriptInterface public String scanMusic() {
            JSONArray out = new JSONArray();
            if (!hasAudioPermission()) return out.toString();
            Uri collection = Build.VERSION.SDK_INT >= 29
                    ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            String[] projection = {
                    MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.RELATIVE_PATH
            };
            try (Cursor c = getContentResolver().query(collection, projection, null, null,
                    MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC")) {
                if (c == null) return out.toString();
                int idCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int nameCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
                int titleCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int artistCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int albumCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                int durationCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                int mimeCol=c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE);
                int pathCol=c.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH);
                while(c.moveToNext()) {
                    long id=c.getLong(idCol);
                    String name=c.getString(nameCol);
                    String title=c.getString(titleCol);
                    if(title==null || title.trim().isEmpty()) title=name;
                    String artist=c.getString(artistCol);
                    String album=c.getString(albumCol);
                    JSONObject o=new JSONObject();
                    o.put("id","native:"+id);
                    o.put("mediaId",id);
                    o.put("name",name==null?"":name);
                    o.put("title",title==null?"Untitled":title);
                    o.put("artist",artist==null||artist.trim().isEmpty()?"Unknown artist":artist);
                    o.put("album",album==null||album.trim().isEmpty()?"Unknown album":album);
                    o.put("genre","Unknown");
                    o.put("year","");
                    o.put("folder",pathCol>=0 && !c.isNull(pathCol)?c.getString(pathCol):"Music");
                    o.put("duration",c.isNull(durationCol)?0:c.getLong(durationCol)/1000.0);
                    o.put("mime",c.isNull(mimeCol)?"audio/mpeg":c.getString(mimeCol));
                    o.put("url",ContentUris.withAppendedId(collection,id).toString());
                    o.put("native",true);
                    out.put(o);
                }
            } catch(Exception ignored) {}
            return out.toString();
        }

        @JavascriptInterface public void downloadMusic(String url, String title, String mime) {
            if (url == null || !url.startsWith("https://archive.org/")) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Downloads are limited to supported legal music sources.", Toast.LENGTH_SHORT).show());
                return;
            }
            io.execute(() -> {
                Uri collection = Build.VERSION.SDK_INT >= 29
                        ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                        : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                String safe = safeFilename(title);
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(MediaStore.Audio.Media.DISPLAY_NAME, safe);
                values.put(MediaStore.Audio.Media.MIME_TYPE, mime == null || mime.isEmpty() ? "audio/mpeg" : mime);
                if (Build.VERSION.SDK_INT >= 29) {
                    values.put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/Universal MP3 Downloads");
                    values.put(MediaStore.Audio.Media.IS_PENDING, 1);
                }
                Uri target = null;
                try {
                    target = getContentResolver().insert(collection, values);
                    if (target == null) throw new Exception("Could not create media file");
                    java.net.HttpURLConnection conn=(java.net.HttpURLConnection)new java.net.URL(url).openConnection();
                    conn.setConnectTimeout(15000); conn.setReadTimeout(30000); conn.setInstanceFollowRedirects(true);
                    conn.setRequestProperty("User-Agent","Universal-MP3-Player/1.0");
                    conn.connect();
                    if(conn.getResponseCode()<200 || conn.getResponseCode()>=300) throw new Exception("HTTP "+conn.getResponseCode());
                    try(InputStream in=conn.getInputStream(); OutputStream out=getContentResolver().openOutputStream(target)) {
                        if(out==null) throw new Exception("Cannot open destination");
                        byte[] buf=new byte[8192]; int n;
                        while((n=in.read(buf))!=-1) out.write(buf,0,n);
                    } finally { conn.disconnect(); }
                    if (Build.VERSION.SDK_INT >= 29) {
                        android.content.ContentValues done=new android.content.ContentValues();
                        done.put(MediaStore.Audio.Media.IS_PENDING,0);
                        getContentResolver().update(target,done,null,null);
                    }
                    Uri finalTarget=target;
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this,"Downloaded to Music/Universal MP3 Downloads",Toast.LENGTH_LONG).show();
                        if(webView!=null) webView.evaluateJavascript("window.nativeDownloadComplete && window.nativeDownloadComplete("+JSONObject.quote(safe)+","+JSONObject.quote(finalTarget.toString())+");",null);
                    });
                } catch(Exception e) {
                    if(target!=null) try { getContentResolver().delete(target,null,null); } catch(Exception ignored) {}
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,"Download failed: "+e.getMessage(),Toast.LENGTH_LONG).show());
                }
            });
        }
    }

    private boolean hasAudioPermission() {
        String p=Build.VERSION.SDK_INT>=33?Manifest.permission.READ_MEDIA_AUDIO:Manifest.permission.READ_EXTERNAL_STORAGE;
        return checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED;
    }

    private String safeFilename(String value) {
        String n=value==null||value.trim().isEmpty()?"music.mp3":value.trim();
        if(!n.toLowerCase(Locale.US).endsWith(".mp3")) n += ".mp3";
        return n.replaceAll("[\\/:*?\"<>|]","_");
    }

    private android.webkit.ValueCallback<Uri[]> fileCallback;
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=1001||fileCallback==null)return;
        Uri[] result=null;
        if(resultCode==RESULT_OK&&data!=null){
            if(data.getClipData()!=null){int n=data.getClipData().getItemCount();result=new Uri[n];for(int i=0;i<n;i++)result[i]=data.getClipData().getItemAt(i).getUri();}
            else if(data.getData()!=null)result=new Uri[]{data.getData()};
        }
        fileCallback.onReceiveValue(result); fileCallback=null;
    }
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}
    @Override public void onBackPressed(){if(webView.canGoBack())webView.goBack();else super.onBackPressed();}
}
