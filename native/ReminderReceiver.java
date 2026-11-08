package uk.mileagebase.app;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import java.util.Calendar;

/** Car Bluetooth prompt, month-end reminder, and setting things up again after a restart. */
public class ReminderReceiver extends BroadcastReceiver {
    static final String MONTH_END = "uk.mileagebase.app.MONTH_END";
    static final String WEEK_END = "uk.mileagebase.app.WEEK_END";
    static final String BACKUP = "uk.mileagebase.app.BACKUP";
    static final String HOME_CHECK = "uk.mileagebase.app.HOME_CHECK", SORT = "uk.mileagebase.app.SORT", JOB = "uk.mileagebase.app.JOB";
    static final int ID_DONE = 7100;
    static final String CHANNEL = "reminders";
    static final int ID_WEEK = 7005, ID_MONTH = 7001, ID_CAR = 7002, ID_OPEN = 7003, ID_RESUME = 7004, ID_BACKUP = 7006;

    @Override
    public void onReceive(Context c, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (a == null) return;
        SharedPreferences p = TrackingService.prefs(c);
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            schedule(c);
            WidgetProvider.refresh(c);
            if (p.getBoolean("active", false)) { // carry on logging by itself; ask for a tap only if Android won't allow it
                try { androidx.core.content.ContextCompat.startForegroundService(c, new Intent(c, TrackingService.class)); }
                catch (Exception e) { notify(c, ID_RESUME, "Trip still open", "The phone restarted. Tap to carry on logging.", "open", false); }
            }
            return;
        }
        if (HOME_CHECK.equals(a)) { if (p.getBoolean("active", false)) tellService(c, TrackingService.ACTION_HOME); return; }
        if (SORT.equals(a) || JOB.equals(a)) { sorted(c, intent); return; }
        if (WEEK_END.equals(a)) {
            String w = p.getString("weekText", "");
            if (!w.isEmpty()) notify(c, ID_WEEK, "Your week", w, "open", false);
            schedule(c);
            return;
        }
        if (BACKUP.equals(a)) { // every 3 days: a reminder that opens the email with the log attached
            long last = p.getLong("lastEmail", 0);
            if (!p.getString("backupEmail", "").isEmpty() && System.currentTimeMillis() - last > 3 * 86_400_000L - 3_600_000L)
                notify(c, ID_BACKUP, "Back up your mileage log", "Tap, then press Send. It goes to " + p.getString("backupEmail", "") + ".", "backup", false);
            schedule(c);
            return;
        }
        if (MONTH_END.equals(a)) {
            notify(c, ID_MONTH, "Month end", "Add the dash reading and send last month's mileage sheet.", "monthend", false);
            schedule(c);
            return;
        }
        boolean in = BluetoothDevice.ACTION_ACL_CONNECTED.equals(a) || "android.app.action.ENTER_CAR_MODE".equals(a);
        boolean out = BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(a) || "android.app.action.EXIT_CAR_MODE".equals(a);
        if (!in && !out) return;
        if (a.startsWith("android.bluetooth")) {
            BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (d == null || !isCar(c, d)) return;
        }
        p.edit().putLong(in ? "carIn" : "carOut", System.currentTimeMillis()).apply();
        if (in) {
            if (p.getBoolean("active", false)) tellService(c, TrackingService.ACTION_WAKE); // already logging: make sure GPS is on
            else autoStart(c);
        } else if (p.getBoolean("active", false)) {
            tellService(c, TrackingService.ACTION_PARK); // left the car: GPS off until you drive again
        }
    }

    /** Your linked car, or, if none is linked, anything that says it's a car (car audio or hands-free kit). */
    static boolean isCar(Context c, BluetoothDevice d) {
        try {
            String addr = d.getAddress();
            String cars = TrackingService.prefs(c).getString("cars", "");
            if (!cars.isEmpty()) return ("," + cars + ",").contains("," + addr + ",");
            return looksLikeCar(d);
        } catch (SecurityException e) { return false; }
    }

    static boolean looksLikeCar(BluetoothDevice d) {
        try {
            android.bluetooth.BluetoothClass bc = d.getBluetoothClass();
            if (bc == null) return false;
            int dc = bc.getDeviceClass();
            return dc == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO
                || dc == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE;
        } catch (SecurityException e) { return false; }
    }

    static void tellService(Context c, String action) {
        try { androidx.core.content.ContextCompat.startForegroundService(c, new Intent(c, TrackingService.class).setAction(action)); }
        catch (Exception e) { // Android wouldn't let it start from the background: say so, don't fail silently
            if (TrackingService.ACTION_WAKE.equals(action)) notify(c, ID_CAR, "In the car", "Tap to carry on logging this trip.", "open", false);
        }
    }

    /** "Trip done": Business and Private buttons, so most trips are sorted without opening the app. */
    static void tripDone(Context c, String tag, double mi) {
        NotificationManager nm = channel(c); if (nm == null) return;
        NotificationCompat.Builder b = base(c, String.format(java.util.Locale.UK, "Trip done: %.0f mi", mi), "Parked at home or the office. Business or private?", "open", ID_DONE);
        b.addAction(0, "Business", sortIntent(c, tag, "bus", 1));
        b.addAction(0, "Private", sortIntent(c, tag, "priv", 2));
        b.addAction(0, "Later", openApp(c, "open", ID_DONE + 3));
        try { nm.notify(ID_DONE, b.build()); } catch (Exception e) { /* notifications off */ }
    }
    private static PendingIntent sortIntent(Context c, String tag, String kind, int n) {
        Intent i = new Intent(c, ReminderReceiver.class).setAction(SORT).putExtra("tag", tag).putExtra("kind", kind);
        return PendingIntent.getBroadcast(c, 9200 + n, i, PendingIntent.FLAG_UPDATE_CURRENT | immutable());
    }
    /** A button was tapped on "Trip done": keep the answer for the app, then ask for the job number if it's business. */
    private static void sorted(Context c, Intent intent) {
        SharedPreferences p = TrackingService.prefs(c);
        String tag = intent.getStringExtra("tag"); if (tag == null) return;
        NotificationManager nm = channel(c); if (nm == null) return;
        if (SORT.equals(intent.getAction())) {
            String kind = intent.getStringExtra("kind");
            p.edit().putString("sort-" + tag, kind).apply();
            if ("bus".equals(kind)) {
                NotificationCompat.Builder b = base(c, "Business saved", "Job number? Leave it if it's a site you've been to before.", "open", ID_DONE);
                androidx.core.app.RemoteInput ri = new androidx.core.app.RemoteInput.Builder("job").setLabel("Job number").build();
                Intent ji = new Intent(c, ReminderReceiver.class).setAction(JOB).putExtra("tag", tag);
                int fl = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0); // typed text has to reach it
                PendingIntent jp = PendingIntent.getBroadcast(c, 9210, ji, fl);
                b.addAction(new NotificationCompat.Action.Builder(0, "Add job number", jp).addRemoteInput(ri).build());
                try { nm.notify(ID_DONE, b.build()); } catch (Exception e) { /* ignore */ }
            } else {
                NotificationCompat.Builder b = base(c, "Private saved", "Only the miles go on the sheet.", "open", ID_DONE).setTimeoutAfter(8000);
                try { nm.notify(ID_DONE, b.build()); } catch (Exception e) { /* ignore */ }
            }
        } else {
            android.os.Bundle r = androidx.core.app.RemoteInput.getResultsFromIntent(intent);
            CharSequence job = r == null ? null : r.getCharSequence("job");
            if (job != null && job.toString().trim().length() > 0) p.edit().putString("job-" + tag, job.toString().trim()).apply();
            NotificationCompat.Builder b = base(c, "Saved", job == null ? "Business trip saved." : "Business, job " + job.toString().trim() + ".", "open", ID_DONE).setTimeoutAfter(8000);
            try { nm.notify(ID_DONE, b.build()); } catch (Exception e) { /* ignore */ }
        }
    }
    private static NotificationManager channel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Car, month-end and open-trip reminders");
            nm.createNotificationChannel(ch);
        }
        return nm;
    }
    private static NotificationCompat.Builder base(Context c, String title, String text, String action, int id) {
        return new NotificationCompat.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle(title).setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH)
                .setColor(0xFFFF6A1A).setContentIntent(openApp(c, action, id)).setOnlyAlertOnce(true);
    }

    /** Starts logging with no tap. If Android won't allow it from the background, asks with a notification instead. */
    static void autoStart(Context c) {
        SharedPreferences p = TrackingService.prefs(c);
        boolean wasOpen = p.getBoolean("tripOpen", false);
        SharedPreferences.Editor e = p.edit().putBoolean("active", true).putString("mode", "starting");
        if (!wasOpen) {
            e.putLong("meters", Double.doubleToLongBits(0)).putLong("since", System.currentTimeMillis()).putBoolean("auto", true).putLong("maxAway", 0L).putLong("resetAt", System.currentTimeMillis());
            TrackingService.routeFile(c).delete();
            TrackingService.stopsFile(c).delete();
        }
        e.apply();
        try {
            androidx.core.content.ContextCompat.startForegroundService(c, new Intent(c, TrackingService.class));
        } catch (Exception ex) {
            p.edit().putBoolean("active", false).putString("mode", "stopped").apply();
            notify(c, ID_CAR, "In the car", "Tap to start logging this trip.", "autostart", false);
        }
    }

    /** Next month-end reminder: 9am on the 1st. */
    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 9);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        cal.add(Calendar.MONTH, 1);
        Intent i = new Intent(c, ReminderReceiver.class).setAction(MONTH_END);
        PendingIntent pi = PendingIntent.getBroadcast(c, 9001, i, PendingIntent.FLAG_UPDATE_CURRENT | immutable());
        try { am.set(AlarmManager.RTC_WAKEUP, cal.getTimeInMillis(), pi); } catch (Exception e) { /* ignore */ }
        // Friday 5pm: the week's miles
        Calendar f = Calendar.getInstance();
        f.set(Calendar.HOUR_OF_DAY, 17); f.set(Calendar.MINUTE, 0); f.set(Calendar.SECOND, 0); f.set(Calendar.MILLISECOND, 0);
        while (f.get(Calendar.DAY_OF_WEEK) != Calendar.FRIDAY || f.getTimeInMillis() <= System.currentTimeMillis()) f.add(Calendar.DAY_OF_MONTH, 1);
        PendingIntent wp = PendingIntent.getBroadcast(c, 9002, new Intent(c, ReminderReceiver.class).setAction(WEEK_END), PendingIntent.FLAG_UPDATE_CURRENT | immutable());
        try { am.set(AlarmManager.RTC_WAKEUP, f.getTimeInMillis(), wp); } catch (Exception e) { /* ignore */ }
        // 7pm each day: the backup reminder, shown only when the last backup email was 3 or more days ago
        Calendar b = Calendar.getInstance();
        b.set(Calendar.HOUR_OF_DAY, 19); b.set(Calendar.MINUTE, 0); b.set(Calendar.SECOND, 0); b.set(Calendar.MILLISECOND, 0);
        if (b.getTimeInMillis() <= System.currentTimeMillis()) b.add(Calendar.DAY_OF_MONTH, 1);
        PendingIntent bp = PendingIntent.getBroadcast(c, 9003, new Intent(c, ReminderReceiver.class).setAction(BACKUP), PendingIntent.FLAG_UPDATE_CURRENT | immutable());
        try { am.set(AlarmManager.RTC_WAKEUP, b.getTimeInMillis(), bp); } catch (Exception e) { /* ignore */ }
    }

    static int immutable() { return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0; }

    static PendingIntent openApp(Context c, String action, int code) {
        Intent i = new Intent(c, MainActivity.class)
                .setAction("uk.mileagebase.app.ACTION_" + action)
                .putExtra("mbp_action", action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | immutable());
    }

    static void notify(Context c, int id, String title, String text, String action, boolean tripButtons) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Car, month-end and open-trip reminders");
            nm.createNotificationChannel(ch);
        }
        NotificationCompat.Builder b = new NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(openApp(c, action, id));
        if (tripButtons) {
            b.addAction(0, "Business", openApp(c, "business", id + 100));
            b.addAction(0, "Private", openApp(c, "private", id + 200));
        }
        try { nm.notify(id, b.build()); } catch (Exception e) { /* notifications off */ }
    }
}
