package by.atmosfera.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Fetches the home city's weather, refreshes widgets and posts rain / severe-weather notifications. */
final class Weather {
    static final String PREFS = "atm";
    static final String CHANNEL = "weather";
    static final int JOB_ID = 42;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        for (JobInfo j : js.getAllPendingJobs()) if (j.getId() == JOB_ID) return;
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, WeatherJob.class))
            .setPeriodic(30 * 60 * 1000L)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build());
    }

    static void refreshAsync(Context c, boolean notify) {
        final Context app = c.getApplicationContext();
        new Thread(() -> update(app, notify)).start();
    }

    static synchronized void update(Context c, boolean allowNotify) {
        SharedPreferences p = prefs(c);
        String home = p.getString("home", null);
        if (home == null || home.equals("null")) { showEmpty(c); return; }
        try {
            JSONObject h = new JSONObject(home);
            double lat = h.getDouble("lat"), lon = h.getDouble("lon");
            String name = h.optString("name", "Мой город");
            String url = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                + "&current=temperature_2m,apparent_temperature,weather_code,is_day,precipitation,wind_speed_10m"
                + "&hourly=temperature_2m,precipitation,snowfall,weather_code,wind_gusts_10m"
                + "&minutely_15=precipitation,snowfall&forecast_minutely_15=12"
                + "&daily=temperature_2m_max,temperature_2m_min&forecast_days=2&forecast_hours=14"
                + "&timezone=auto&wind_speed_unit=ms", lat, lon);
            JSONObject w = new JSONObject(get(url));
            boolean f = "F".equals(p.getString("units", "C"));
            JSONObject cur = w.getJSONObject("current");
            JSONObject daily = w.getJSONObject("daily");
            int code = cur.getInt("weather_code");
            boolean day = cur.optInt("is_day", 1) == 1;
            String temp = deg(cur.getDouble("temperature_2m"), f);
            String desc = describe(code) + " · ощущается " + deg(cur.getDouble("apparent_temperature"), f);
            String range = "Сегодня " + deg(daily.getJSONArray("temperature_2m_min").getDouble(0), f) + " … "
                + deg(daily.getJSONArray("temperature_2m_max").getDouble(0), f)
                + " · обновлено " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
            String rain = rainStory(w);

            RemoteViews v = views(c);
            v.setTextViewText(R.id.w_city, name);
            v.setTextViewText(R.id.w_temp, temp);
            v.setTextViewText(R.id.w_desc, desc);
            v.setTextViewText(R.id.w_range, range);
            v.setTextViewText(R.id.w_now, rain.isEmpty() ? "Ближайшие 3 часа без осадков" : rain);
            v.setImageViewResource(R.id.w_icon, iconFor(code, day));
            push(c, v);

            if (allowNotify && p.getBoolean("notify", false) && canNotify(c)) notifyIfNeeded(c, p, w, name, rain);
        } catch (Exception e) {
            RemoteViews v = views(c);
            v.setTextViewText(R.id.w_now, "Нет связи — обновлю позже");
            push(c, v);
        }
    }

    /* ---- notifications ---- */
    private static void notifyIfNeeded(Context c, SharedPreferences p, JSONObject w, String city, String rain) throws Exception {
        Set<String> sent = new HashSet<>(p.getStringSet("sent", new HashSet<>()));
        String today = w.getJSONObject("current").getString("time").substring(0, 10);
        Set<String> keep = new HashSet<>();
        for (String k : sent) if (k.contains(today)) keep.add(k);
        sent = keep;
        int id = 100;

        // rain or snow starting within the next hour
        if (rain.startsWith("Через")) {
            String key = "rain|" + today + "|" + w.getJSONObject("current").getString("time").substring(11, 13);
            boolean recent = false;
            for (String k : sent) if (k.startsWith("rain|" + today)) {
                int hh = Integer.parseInt(k.substring(k.length() - 2));
                int now = Integer.parseInt(key.substring(key.length() - 2));
                if (Math.abs(now - hh) < 3) recent = true;
            }
            if (!recent) { post(c, id, city + ": " + rain.toLowerCase(Locale.ROOT).replace("через", "через"), "Не забудьте зонт.", 1); sent.add(key); }
        }

        // severe weather in the next 12 hours
        JSONObject hr = w.getJSONObject("hourly");
        JSONArray t = hr.getJSONArray("time"), gust = hr.getJSONArray("wind_gusts_10m"), pr = hr.getJSONArray("precipitation"),
            sn = hr.getJSONArray("snowfall"), wc = hr.getJSONArray("weather_code"), tmp = hr.getJSONArray("temperature_2m");
        double maxGust = 0, maxRain = 0, snow = 0, minT = 99; int thunder = -1, ice = -1, gi = 0, ri = 0, ti = 0;
        int n = Math.min(t.length(), 12);
        for (int i = 0; i < n; i++) {
            if (gust.optDouble(i, 0) > maxGust) { maxGust = gust.optDouble(i, 0); gi = i; }
            if (pr.optDouble(i, 0) > maxRain) { maxRain = pr.optDouble(i, 0); ri = i; }
            snow += sn.optDouble(i, 0);
            if (tmp.optDouble(i, 99) < minT) { minT = tmp.optDouble(i, 99); ti = i; }
            int code = wc.optInt(i, 0);
            if (code >= 95 && thunder < 0) thunder = i;
            if ((code == 56 || code == 57 || code == 66 || code == 67) && ice < 0) ice = i;
        }
        double curT = w.getJSONObject("current").getDouble("temperature_2m");
        if (maxGust >= 15) id = warn(c, sent, today, "wind", ++id, "Сильный ветер в " + inCity(city), "Порывы до " + Math.round(maxGust) + " м/с около " + hh(t, gi) + ".");
        if (thunder >= 0) id = warn(c, sent, today, "storm", ++id, "Гроза в " + inCity(city), "Ожидается около " + hh(t, thunder) + ". Держитесь подальше от деревьев.");
        if (maxRain >= 8) id = warn(c, sent, today, "downpour", ++id, "Ливень в " + inCity(city), "До " + Math.round(maxRain) + " мм за час около " + hh(t, ri) + ".");
        if (snow >= 4) id = warn(c, sent, today, "snow", ++id, "Сильный снегопад в " + inCity(city), "Около " + Math.round(snow) + " см снега за 12 часов.");
        if (ice >= 0) id = warn(c, sent, today, "ice", ++id, "Гололёд в " + inCity(city), "Ледяной дождь или морось около " + hh(t, ice) + ". Осторожно на дорогах.");
        if (minT < 0 && curT > 2) warn(c, sent, today, "frost", ++id, "Заморозки в " + inCity(city), "До " + Math.round(minT) + "° около " + hh(t, ti) + ".");
        p.edit().putStringSet("sent", sent).apply();
    }

    private static int warn(Context c, Set<String> sent, String today, String type, int id, String title, String text) {
        String key = type + "|" + today;
        if (sent.contains(key)) return id;
        post(c, id, title, text, 2);
        sent.add(key);
        return id;
    }

    private static String inCity(String city) { return "«" + city + "»"; }

    private static String hh(JSONArray t, int i) { return t.optString(i, "T--:--").substring(11, 16); }

    static void ensureChannel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Погода: дождь и опасные явления", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Предупреждения о скором дожде, грозе, сильном ветре, гололёде и заморозках в главном городе");
            nm.createNotificationChannel(ch);
        }
    }

    private static void post(Context c, int id, String title, String text, int level) {
        ensureChannel(c);
        PendingIntent pi = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title).setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text))
            .setColor(level >= 2 ? 0xFFFF7A59 : 0xFF7CC4FF)
            .setContentIntent(pi).setAutoCancel(true).build();
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(id, n);
    }

    static boolean canNotify(Context c) {
        return Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    /* ---- widget ---- */
    private static RemoteViews views(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_weather);
        PendingIntent pi = PendingIntent.getActivity(c, 1, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        v.setOnClickPendingIntent(R.id.w_root, pi);
        return v;
    }

    private static void push(Context c, RemoteViews v) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, WeatherWidget.class));
        if (ids.length > 0) m.updateAppWidget(ids, v);
    }

    private static void showEmpty(Context c) {
        RemoteViews v = views(c);
        v.setTextViewText(R.id.w_city, "Атмосфера");
        v.setTextViewText(R.id.w_temp, "—");
        v.setTextViewText(R.id.w_desc, "Откройте приложение и сохраните главный город");
        v.setTextViewText(R.id.w_range, "");
        v.setTextViewText(R.id.w_now, "");
        push(c, v);
    }

    /* ---- helpers ---- */
    private static String rainStory(JSONObject w) throws Exception {
        if (!w.has("minutely_15")) return "";
        JSONObject m = w.getJSONObject("minutely_15");
        JSONArray t = m.getJSONArray("time"), p = m.getJSONArray("precipitation"), s = m.getJSONArray("snowfall");
        double nowP = w.getJSONObject("current").optDouble("precipitation", 0);
        boolean wetNow = p.optDouble(0, 0) >= 0.1 || nowP > 0;
        for (int i = 1; i < Math.min(t.length(), 12); i++) {
            boolean wet = p.optDouble(i, 0) >= 0.1;
            if (!wetNow && wet) {
                String kind = s.optDouble(i, 0) > 0 ? "снег" : "дождь";
                return "Через " + (i * 15) + " мин начнётся " + kind;
            }
            if (wetNow && !wet && p.optDouble(Math.min(i + 1, t.length() - 1), 0) < 0.1) {
                return "Осадки закончатся около " + t.getString(i).substring(11, 16);
            }
        }
        return wetNow ? "Осадки продлятся ещё несколько часов" : "";
    }

    private static String deg(double v, boolean f) { return Math.round(f ? v * 9 / 5 + 32 : v) + "°"; }

    static String describe(int c) {
        if (c == 0) return "Ясно"; if (c == 1) return "Преимущественно ясно"; if (c == 2) return "Переменная облачность"; if (c == 3) return "Пасмурно";
        if (c == 45 || c == 48) return "Туман"; if (c >= 51 && c <= 57) return "Морось"; if (c >= 61 && c <= 67) return "Дождь";
        if (c >= 71 && c <= 77) return "Снег"; if (c >= 80 && c <= 82) return "Ливень"; if (c == 85 || c == 86) return "Снегопад";
        if (c >= 95) return "Гроза"; return "—";
    }

    static int iconFor(int c, boolean day) {
        if (c <= 1) return day ? R.drawable.wx_clear_day : R.drawable.wx_clear_night;
        if (c == 2) return day ? R.drawable.wx_partly_day : R.drawable.wx_partly_night;
        if (c == 3) return R.drawable.wx_cloud;
        if (c == 45 || c == 48) return R.drawable.wx_fog;
        if (c >= 51 && c <= 57) return R.drawable.wx_drizzle;
        if ((c >= 71 && c <= 77) || c == 85 || c == 86) return R.drawable.wx_snow;
        if (c >= 95) return R.drawable.wx_storm;
        return R.drawable.wx_rain;
    }

    private static String get(String u) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(u).openConnection();
        con.setConnectTimeout(15000); con.setReadTimeout(15000);
        con.setRequestProperty("User-Agent", "AtmosferaApp/1.4");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(con.getInputStream(), "UTF-8"))) {
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally { con.disconnect(); }
    }
}
