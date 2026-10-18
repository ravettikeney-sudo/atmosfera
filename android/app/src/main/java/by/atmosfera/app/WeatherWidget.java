package by.atmosfera.app;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** Home-screen widget: current weather in the home city; tapping it opens the app. */
public class WeatherWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        final PendingResult r = goAsync();
        final Context app = c.getApplicationContext();
        new Thread(() -> { try { Weather.update(app, false); } finally { r.finish(); } }).start();
        Weather.schedule(app);
    }

    @Override
    public void onEnabled(Context c) { Weather.schedule(c.getApplicationContext()); }
}
