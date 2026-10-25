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
    static final String CHANNEL = "reminders";
    static final int ID_WEEK = 7005, ID_MONTH = 7001, ID_CAR = 7002, ID_OPEN = 7003, ID_RESUME = 7004;

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
        if (MONTH_END.equals(a)) {
            notify(c, ID_MONTH, "Month end", "Enter your odometer reading and send last month's mileage sheet.", "monthend", false);
            schedule(c);
            return;
        }
        if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(a)) {
            BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (d == null) return;
            String addr;
            try { addr = d.getAddress(); } catch (SecurityException e) { return; }
            String cars = "," + p.getString("cars", "") + ",";
            if (addr == null || !cars.contains("," + addr + ",")) return;
            if (p.getBoolean("active", false)) return; // already logging
            autoStart(c);
        }
    }

    /** Starts logging with no tap. If Android won't allow it from the background, asks with a notification instead. */
    static void autoStart(Context c) {
        SharedPreferences p = TrackingService.prefs(c);
        boolean wasOpen = p.getBoolean("tripOpen", false);
        SharedPreferences.Editor e = p.edit().putBoolean("active", true).putString("mode", "starting");
        if (!wasOpen) {
            e.putLong("meters", Double.doubleToLongBits(0)).putLong("since", System.currentTimeMillis()).putBoolean("auto", true);
            TrackingService.routeFile(c).delete();
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
