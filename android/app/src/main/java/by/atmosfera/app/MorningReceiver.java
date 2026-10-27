package by.atmosfera.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fires once a day at the chosen time: posts the morning summary and books the next day. */
public class MorningReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        final PendingResult r = goAsync();
        final Context app = c.getApplicationContext();
        Weather.scheduleMorning(app);
        new Thread(() -> { try { Weather.morning(app); } finally { r.finish(); } }).start();
    }
}
