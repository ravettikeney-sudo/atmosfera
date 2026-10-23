package by.atmosfera.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Checks version.json on the site at every launch. When a newer APK is published it offers to update,
 * downloads it and hands it to Android's installer, so the user only confirms one system dialog.
 */
final class Updater {
    static final String BASE = "https://ravettikeney-sudo.github.io/atmosfera/";
    private static boolean busy = false;
    private static boolean waitingForPermission = false;
    private static String pendingApk = null;

    static void check(Activity a) {
        if (busy) return;
        new Thread(() -> {
            try {
                JSONObject v = new JSONObject(get(BASE + "version.json?t=" + System.currentTimeMillis()));
                int latest = v.getInt("versionCode");
                if (latest <= currentVersion(a)) return;
                String name = v.optString("versionName", "");
                String notes = v.optString("notes", "");
                String apk = BASE + v.optString("apk", "atmosfera.apk") + "?v=" + latest;
                a.runOnUiThread(() -> {
                    if (a.isFinishing()) return;
                    new AlertDialog.Builder(a)
                        .setTitle("Доступна новая версия " + name)
                        .setMessage((notes.isEmpty() ? "" : notes + "\n\n") + "Обновление скачается само, затем Android попросит подтвердить установку.")
                        .setPositiveButton("Обновить", (d, w) -> start(a, apk))
                        .setNegativeButton("Позже", null)
                        .show();
                });
            } catch (Exception ignored) { /* offline or site unavailable: try next launch */ }
        }).start();
    }

    /** Called from onResume: if the user just allowed installs from this app, continue the update. */
    static void resume(Activity a) {
        if (waitingForPermission && pendingApk != null && canInstall(a)) {
            waitingForPermission = false;
            start(a, pendingApk);
        }
    }

    private static boolean canInstall(Context c) {
        return Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls();
    }

    private static void start(Activity a, String apkUrl) {
        if (!canInstall(a)) {
            pendingApk = apkUrl; waitingForPermission = true;
            Toast.makeText(a, "Разрешите «Атмосфере» устанавливать обновления и вернитесь назад", Toast.LENGTH_LONG).show();
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            return;
        }
        busy = true;
        Toast.makeText(a, "Скачиваю обновление…", Toast.LENGTH_SHORT).show();
        final Context app = a.getApplicationContext();
        new Thread(() -> {
            try {
                PackageInstaller pi = app.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(app.getPackageName());
                int id = pi.createSession(params);
                try (PackageInstaller.Session s = pi.openSession(id)) {
                    HttpURLConnection con = (HttpURLConnection) new URL(apkUrl).openConnection();
                    con.setConnectTimeout(20000); con.setReadTimeout(30000);
                    con.setUseCaches(false);
                    try (InputStream in = con.getInputStream(); OutputStream out = s.openWrite("atmosfera.apk", 0, -1)) {
                        byte[] buf = new byte[65536]; int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        s.fsync(out);
                    } finally { con.disconnect(); }
                    Intent done = new Intent(app, InstallResult.class);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    s.commit(PendingIntent.getBroadcast(app, id, done, flags).getIntentSender());
                }
            } catch (Exception e) {
                a.runOnUiThread(() -> Toast.makeText(a, "Не удалось скачать обновление. Попробую при следующем запуске.", Toast.LENGTH_LONG).show());
            } finally { busy = false; }
        }).start();
    }

    private static int currentVersion(Context c) throws PackageManager.NameNotFoundException {
        return (int) c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
    }

    private static String get(String u) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(u).openConnection();
        con.setConnectTimeout(10000); con.setReadTimeout(10000); con.setUseCaches(false);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream(), "UTF-8"))) {
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally { con.disconnect(); }
    }

    /** Receives the installer's answer; shows the system "Install?" screen when it asks for confirmation. */
    public static class InstallResult extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) { confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); c.startActivity(confirm); }
            } else if (status != PackageInstaller.STATUS_SUCCESS && status != PackageInstaller.STATUS_FAILURE_ABORTED) {
                Toast.makeText(c, "Обновление не установилось: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), Toast.LENGTH_LONG).show();
            }
        }
    }
}
