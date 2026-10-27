package by.atmosfera.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** After a phone restart, books the morning summary again (the weather job survives restarts by itself). */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction()) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(i.getAction())) {
            Context app = c.getApplicationContext();
            Weather.scheduleMorning(app);
            if (Weather.prefs(app).getBoolean("notify", false)) Weather.schedule(app);
        }
    }
}
