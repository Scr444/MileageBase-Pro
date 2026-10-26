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
            if (p.getBoolean("active", false)) {
                notify(c, ID_RESUME, "Trip still open", "The phone restarted. Open DrivR to carry on logging.", "open", false);
            }
            return;
        }
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
            notify(c, ID_MONTH, "Month end", "Enter your odometer reading and send last month's mileage sheet.", "monthend", false);
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
        catch (Exception e) { /* picked up next time the app opens */ }
    }

    /** Starts logging with no tap. If Android won't allow it from the background, asks with a notification instead. */
    static void autoStart(Context c) {
        SharedPreferences p = TrackingService.prefs(c);
        boolean wasOpen = p.getBoolean("tripOpen", false);
        SharedPreferences.Editor e = p.edit().putBoolean("active", true).putString("mode", "starting");
        if (!wasOpen) {
            e.putLong("meters", Double.doubleToLongBits(0)).putLong("since", System.currentTimeMillis()).putBoolean("auto", true).putLong("maxAway", 0L);
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
