package com.arcaneledger.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Base64;
import android.view.ViewGroup;
import android.view.HapticFeedbackConstants;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;

public class MainActivity extends Activity {
    private static final int FILE_PICKER = 41;
    private static final String RELEASE_API = "https://api.github.com/repos/BearJ3rk/MTG-Life-Scouter/releases/latest";
    private static final String RELEASE_PAGE = "https://github.com/BearJ3rk/MTG-Life-Scouter/releases/latest";
    private static final String UPDATE_PREFS = "update-download";
    private static final String UPDATE_DOWNLOAD_ID = "download-id";
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private long updateDownloadId = -1;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id == updateDownloadId) installDownloadedApk(id);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout appFrame = new FrameLayout(this);
        webView = new WebView(this);
        int tableBackground = Color.rgb(14, 15, 13);
        appFrame.setBackgroundColor(tableBackground);
        webView.setBackgroundColor(tableBackground);
        appFrame.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        setContentView(appFrame);
        appFrame.setOnApplyWindowInsetsListener((view, insets) -> {
            int extraRightCushion = Math.round(16 * getResources().getDisplayMetrics().density);
            int minimumRight = Math.round(56 * getResources().getDisplayMetrics().density);
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(bars.left, bars.top, Math.max(bars.right + extraRightCushion, minimumRight), bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        Math.max(insets.getSystemWindowInsetRight() + extraRightCushion, minimumRight), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        appFrame.requestApplyInsets();
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        webView.setOnLongClickListener(view -> true);
        webView.setHapticFeedbackEnabled(true);
        webView.addJavascriptInterface(new UpdaterBridge(), "AndroidUpdater");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                startActivityForResult(intent, FILE_PICKER);
                return true;
            }
        });
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloadReceiver, filter, RECEIVER_EXPORTED);
        else registerReceiver(downloadReceiver, filter);
        webView.loadUrl("file:///android_asset/index.html");
    }

    public class UpdaterBridge {
        @JavascriptInterface public void checkForUpdates() {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Checking GitHub for updates…", Toast.LENGTH_SHORT).show());
            new Thread(MainActivity.this::fetchLatestRelease).start();
        }

        @JavascriptInterface public void searchCardArt(String query, int playerIndex) {
            new Thread(() -> fetchCardArt(query, playerIndex)).start();
        }

        @JavascriptInterface public void setKeepAwake(boolean enabled) {
            runOnUiThread(() -> {
                if (enabled) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface public void feedback() {
            runOnUiThread(() -> webView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP));
        }

        @JavascriptInterface public void downloadUpdate(String url, String fileName, String version) {
            startUpdateDownload(url, fileName, version);
        }
    }

    private void fetchCardArt(String query, int playerIndex) {
        HttpURLConnection cardConnection = null;
        HttpURLConnection imageConnection = null;
        try {
            String endpoint = "https://api.scryfall.com/cards/named?fuzzy=" + URLEncoder.encode(query, "UTF-8");
            cardConnection = (HttpURLConnection) new URL(endpoint).openConnection();
            cardConnection.setRequestProperty("Accept", "application/json;q=0.9,*/*;q=0.8");
            cardConnection.setRequestProperty("User-Agent", "MTG-Life-Scouter/0.14");
            if (cardConnection.getResponseCode() != 200) throw new Exception("Card not found.");
            BufferedReader reader = new BufferedReader(new InputStreamReader(cardConnection.getInputStream()));
            StringBuilder json = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) json.append(line);
            JSONObject card = new JSONObject(json.toString());
            JSONObject images;
            if (card.has("image_uris")) images = card.getJSONObject("image_uris");
            else images = card.getJSONArray("card_faces").getJSONObject(0).getJSONObject("image_uris");
            String imageUrl = images.has("art_crop") ? images.getString("art_crop") : images.getString("normal");
            imageConnection = (HttpURLConnection) new URL(imageUrl).openConnection();
            imageConnection.setRequestProperty("User-Agent", "MTG-Life-Scouter/0.14");
            if (imageConnection.getResponseCode() != 200) throw new Exception("Artwork could not be downloaded.");
            String mime = imageConnection.getContentType();
            if (mime == null || !mime.startsWith("image/")) mime = "image/jpeg";
            InputStream input = imageConnection.getInputStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > 4 * 1024 * 1024) throw new Exception("Artwork is too large to save locally.");
                output.write(buffer, 0, read);
            }
            input.close();
            String dataUrl = "data:" + mime + ";base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
            sendCardArtResult(playerIndex, card.getString("name"), dataUrl, null);
        } catch (Exception error) {
            String message = error.getMessage();
            sendCardArtResult(playerIndex, null, null, message == null || message.isEmpty() ? "Card search failed. Check your connection." : message);
        } finally {
            if (cardConnection != null) cardConnection.disconnect();
            if (imageConnection != null) imageConnection.disconnect();
        }
    }

    private void sendCardArtResult(int playerIndex, String name, String dataUrl, String error) {
        String script = "receiveCardArt(" + playerIndex + "," + jsonString(name) + "," +
                jsonString(dataUrl) + "," + jsonString(error) + ")";
        runOnUiThread(() -> webView.evaluateJavascript(script, null));
    }

    private String jsonString(String value) {
        return value == null ? "null" : JSONObject.quote(value);
    }

    private void fetchLatestRelease() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(RELEASE_API).openConnection();
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "MTG-Life-Scouter-Android");
            int responseCode = connection.getResponseCode();
            if (responseCode == 403 || responseCode == 404) {
                openReleasesPage("GitHub access is required for this private repository. Opening releases…");
                return;
            }
            if (responseCode < 200 || responseCode >= 300) throw new Exception("GitHub HTTP " + responseCode);
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder json = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) json.append(line);
            JSONObject release = new JSONObject(json.toString());
            String latestTag = release.getString("tag_name");
            String installedVersion = currentVersion();
            if (versionNumber(latestTag) <= versionNumber(installedVersion)) {
                notifyUser("MTG Life Scouter is already up to date (" + installedVersion + ").");
                return;
            }
            JSONArray assets = release.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                String name = asset.getString("name");
                if (name.endsWith(".apk")) {
                    offerUpdate(latestTag, release.optString("body", ""), asset.getString("browser_download_url"), name);
                    return;
                }
            }
            notifyUser("The latest release does not contain an APK.");
        } catch (Exception error) {
            openReleasesPage("Automatic check was unavailable. Opening GitHub releases…");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void offerUpdate(String version, String notes, String url, String fileName) {
        String script = "showUpdateAvailable(" + jsonString(version) + "," + jsonString(notes) + "," +
                jsonString(url) + "," + jsonString(fileName) + ")";
        runOnUiThread(() -> webView.evaluateJavascript(script, null));
    }

    private void openReleasesPage(String message) {
        runOnUiThread(() -> {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASE_PAGE)));
        });
    }

    private long versionNumber(String version) {
        String[] parts = version.replaceAll("[^0-9.]", "").split("\\.");
        long value = 0;
        for (int i = 0; i < Math.min(parts.length, 3); i++) value = value * 1000 + Long.parseLong(parts[i]);
        for (int i = parts.length; i < 3; i++) value *= 1000;
        return value;
    }

    private String currentVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception error) {
            return "0.0.0";
        }
    }

    private void startUpdateDownload(String url, String fileName, String version) {
        runOnUiThread(() -> {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle("MTG Life Scouter " + version);
            request.setDescription("Downloading signed update");
            request.setMimeType("application/vnd.android.package-archive");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            updateDownloadId = manager.enqueue(request);
            getSharedPreferences(UPDATE_PREFS, MODE_PRIVATE).edit().putLong(UPDATE_DOWNLOAD_ID, updateDownloadId).apply();
            Toast.makeText(this, "Downloading " + version + "…", Toast.LENGTH_LONG).show();
        });
    }

    private void installDownloadedApk(long id) {
        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id));
        if (cursor == null || !cursor.moveToFirst()) return;
        int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
        cursor.close();
        if (status == DownloadManager.STATUS_FAILED) {
            getSharedPreferences(UPDATE_PREFS, MODE_PRIVATE).edit().remove(UPDATE_DOWNLOAD_ID).apply();
            notifyUser("Update download failed.");
            return;
        }
        if (status != DownloadManager.STATUS_SUCCESSFUL) return;
        Uri apk = manager.getUriForDownloadedFile(id);
        if (apk == null) { notifyUser("The downloaded update could not be opened."); return; }
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "Allow MTG Life Scouter to install updates. The installer will open when you return.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            return;
        }
        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(apk, "application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(install);
            updateDownloadId = -1;
            getSharedPreferences(UPDATE_PREFS, MODE_PRIVATE).edit().remove(UPDATE_DOWNLOAD_ID).apply();
        } catch (Exception error) {
            notifyUser("Android could not open the update installer. Tap the completed download notification to install it.");
        }
    }

    @Override protected void onResume() {
        super.onResume();
        long savedId = getSharedPreferences(UPDATE_PREFS, MODE_PRIVATE).getLong(UPDATE_DOWNLOAD_ID, -1);
        if (savedId != -1) {
            updateDownloadId = savedId;
            installDownloadedApk(savedId);
        }
    }

    private void notifyUser(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_LONG).show());
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_PICKER || fileCallback == null) return;
        Uri[] result = resultCode == RESULT_OK && data != null && data.getData() != null ? new Uri[]{data.getData()} : null;
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    @Override protected void onDestroy() {
        unregisterReceiver(downloadReceiver);
        webView.removeJavascriptInterface("AndroidUpdater");
        webView.destroy();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }
}
