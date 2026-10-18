package by.atmosfera.app;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Runs about every 30 minutes: refreshes the widget and checks for rain / severe weather. */
public class WeatherJob extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> { Weather.update(getApplicationContext(), true); jobFinished(params, false); }).start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return true; }
}
