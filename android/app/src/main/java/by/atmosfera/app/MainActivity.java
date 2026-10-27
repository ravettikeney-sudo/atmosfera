package by.atmosfera.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.tts.TextToSpeech;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Locale;
import android.view.View;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.view.WindowInsetsController;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Full-screen shell around the Atmosfera web app. Content is loaded live, so the app updates without reinstalling. */
public class MainActivity extends Activity {
    private static final String HOME = "https://ravettikeney-sudo.github.io/atmosfera/";
    private static final String HOST = "ravettikeney-sudo.github.io";
    private static final int REQ_LOCATION = 7;
    private static final int REQ_NOTIFY = 8;
    private static final int REQ_VOICE = 9;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private String pendingSpeech = null;

    private WebView web;
    private String geoOrigin;
    private GeolocationPermissions.Callback geoCallback;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#14234a"));
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " AtmosferaApp/1.4");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) showOffline();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                if (hasLocation()) { cb.invoke(origin, true, false); return; }
                geoOrigin = origin; geoCallback = cb;
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            }
        });

        web.addJavascriptInterface(new Bridge(), "AtmApp");
        Weather.ensureChannel(this);
        Updater.check(this);
        if (Weather.prefs(this).getBoolean("notify", false)) Weather.schedule(this);

        if (state != null) web.restoreState(state);
        else web.loadUrl(HOME);
    }

    /** Called from the web page: which city the widget and notifications follow, units, notifications on/off, theme. */
    private class Bridge {
        @JavascriptInterface public void setHome(String json) {
            String old = Weather.prefs(MainActivity.this).getString("home", "");
            Weather.prefs(MainActivity.this).edit().putString("home", json).apply();
            if (!json.equals(old)) Weather.refreshAsync(MainActivity.this, false);
        }
        @JavascriptInterface public void setUnits(String u) {
            String old = Weather.prefs(MainActivity.this).getString("units", "C");
            Weather.prefs(MainActivity.this).edit().putString("units", u).apply();
            if (!u.equals(old)) Weather.refreshAsync(MainActivity.this, false);
        }
        @JavascriptInterface public void setNotify(boolean on) {
            boolean was = Weather.prefs(MainActivity.this).getBoolean("notify", false);
            Weather.prefs(MainActivity.this).edit().putBoolean("notify", on).apply();
            if (on) {
                Weather.schedule(MainActivity.this);
                if (!was && Build.VERSION.SDK_INT >= 33 && !Weather.canNotify(MainActivity.this))
                    runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY));
            }
        }
        @JavascriptInterface public void listen() {
            runOnUiThread(() -> {
                Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
                i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Спросите о погоде");
                try { startActivityForResult(i, REQ_VOICE); }
                catch (Exception e) { js("window.atmVoiceError && window.atmVoiceError()"); }
            });
        }
        @JavascriptInterface public void speak(String text) {
            runOnUiThread(() -> {
                if (tts == null) {
                    pendingSpeech = text;
                    tts = new TextToSpeech(MainActivity.this, status -> {
                        ttsReady = status == TextToSpeech.SUCCESS;
                        if (ttsReady) { tts.setLanguage(new Locale("ru", "RU")); if (pendingSpeech != null) { tts.speak(pendingSpeech, TextToSpeech.QUEUE_FLUSH, null, "atm"); pendingSpeech = null; } }
                    });
                } else if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "atm");
                else pendingSpeech = text;
            });
        }
        @JavascriptInterface public void stopSpeak() { runOnUiThread(() -> { if (tts != null) tts.stop(); }); }
        @JavascriptInterface public void setTheme(String t) {
            runOnUiThread(() -> {
                boolean light = "light".equals(t);
                int bg = light ? Color.parseColor("#eef3f9") : Color.parseColor("#14234a");
                getWindow().setStatusBarColor(bg);
                getWindow().setNavigationBarColor(bg);
                if (Build.VERSION.SDK_INT >= 30) {
                    WindowInsetsController c = getWindow().getInsetsController();
                    if (c != null) c.setSystemBarsAppearance(light ? (WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS) : 0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                } else {
                    getWindow().getDecorView().setSystemUiVisibility(light ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0);
                }
            });
        }
    }

    private void js(String code) { web.post(() -> web.evaluateJavascript(code, null)); }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQ_VOICE) return;
        ArrayList<String> r = data == null ? null : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (result == RESULT_OK && r != null && !r.isEmpty()) js("window.atmVoiceResult && window.atmVoiceResult(" + JSONObject.quote(r.get(0)) + ")");
        else js("window.atmVoiceError && window.atmVoiceError()");
    }

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_NOTIFY && !Weather.canNotify(this)) {
            web.evaluateJavascript("toast('Уведомления запрещены. Разрешите их в настройках Android: Приложения → Атмосфера → Уведомления.')", null);
        }
        if (code == REQ_LOCATION && geoCallback != null) {
            geoCallback.invoke(geoOrigin, hasLocation(), false);
            geoCallback = null;
        }
    }

    private void showOffline() {
        String html = "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
            + "<body style='margin:0;background:#14234a;color:#e8eef8;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;text-align:center'>"
            + "<div style='padding:24px'><h2 style='font-weight:700'>Нет подключения к интернету</h2>"
            + "<p style='color:#8fa0b8'>Погоде нужен интернет. Проверьте Wi-Fi или мобильные данные.</p>"
            + "<button onclick=\"location.href='" + HOME + "'\" style='margin-top:12px;padding:12px 22px;border:0;border-radius:999px;background:#7cc4ff;color:#14234a;font-size:16px;font-weight:600'>Повторить</button></div></body></html>";
        web.loadDataWithBaseURL(HOME, html, "text/html", "utf-8", HOME);
    }

    @Override
    public void onBackPressed() {
        // Let the page close its own panels first (sheet, weather map, lists); exit only when nothing is open.
        web.evaluateJavascript("(function(){try{return window.atmBack?window.atmBack():false}catch(e){return false}})()", value -> {
            if ("true".equals(value)) return;
            if (web.canGoBack()) web.goBack(); else finish();
        });
    }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); web.saveState(out); }
    @Override protected void onPause() { super.onPause(); web.onPause(); Weather.refreshAsync(this, false); }
    @Override protected void onResume() { super.onResume(); web.onResume(); Updater.resume(this); }
    @Override protected void onDestroy() { if (tts != null) tts.shutdown(); web.destroy(); super.onDestroy(); }
}
