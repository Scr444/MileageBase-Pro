package uk.mileagebase.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import androidx.core.app.NotificationCompat;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Logs miles while a trip is open.
 * Driving: full GPS every 2 s; only movement at driving speed counts.
 * Parked (stopped 5 min): GPS off. A rough network fix every 90 s and the phone's
 * motion sensor watch for setting off again, then full GPS comes back.
 */
public class TrackingService extends Service {
    static final String PREFS = "mbp_tracker";
    static final String CHANNEL = "drive";
    static final int NOTE_ID = 4117;
    static final float DRIVING_MS = 4.47f;      // 10 mph
    static final float CREEP_MS = 2.0f;         // stop-start traffic, counted only during a drive
    static final long DRIVE_SPELL_MS = 120_000L; // traffic counts within 2 min of driving speed
    static final long PARK_AFTER_MS = 300_000L;  // stopped 5 min = parked
    static final long WAKE_GRACE_MS = 120_000L;  // after a wake-up, park again if no driving within 2 min
    static final long NET_EVERY_MS = 90_000L;
    static final long PARK_GPS_MS = 60_000L;     // parked: a GPS check every minute, so driving off is never missed
    static final float MOVED_M = 200f;           // parked: this far from where you stopped = you've driven off
    static final double ROAD_FACTOR = 1.2;       // a late wake-up: straight-line gap x 1.2, roughly the road distance
    static final String ACTION_WAKE = "uk.mileagebase.app.WAKE", ACTION_PARK = "uk.mileagebase.app.PARK";
    private Location wakeFrom;
    static final float MAX_ACCURACY_M = 35f;

    private LocationManager lm;
    private SensorManager sm;
    private Sensor motion;
    private Handler handler;
    private Location last, anchor;
    private long lastDriving, lastMoving;
    private boolean parked, running;
    private double meters;
    private double notedMiles = -1;
    private Location routeLast;
    static final float ROUTE_STEP_M = 40f;

    static File routeFile(Context c) { return new File(c.getFilesDir(), "route.txt"); }

    /** Keeps a point every 40 m or so, for the route map. */
    private void routePoint(Location l) {
        if (routeLast != null && l.distanceTo(routeLast) < ROUTE_STEP_M) return;
        routeLast = l;
        String line = String.format(Locale.UK, "%.5f,%.5f\n", l.getLatitude(), l.getLongitude());
        try (FileOutputStream out = new FileOutputStream(routeFile(this), true)) {
            out.write(line.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { /* the miles still count */ }
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    static double readMeters(Context c) { return Double.longBitsToDouble(prefs(c).getLong("meters", 0L)); }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences p = prefs(this);
        if (!p.getBoolean("active", false)) { stopSelf(); return START_NOT_STICKY; }
        meters = readMeters(this);
        makeChannel();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            else startForeground(NOTE_ID, note());
        } catch (Exception e) {
            // Android refused to start logging in the background: ask for a tap instead
            p.edit().putBoolean("active", false).putString("mode", "stopped").apply();
            ReminderReceiver.notify(this, ReminderReceiver.ID_CAR, "Trip logging", "Tap to start logging this trip.", "autostart", false);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) {
            running = true;
            lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            motion = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION);
            handler = new Handler(Looper.getMainLooper());
            long now = System.currentTimeMillis();
            lastDriving = 0; lastMoving = now - PARK_AFTER_MS + WAKE_GRACE_MS * 2;
            // GPS can take a minute or two to lock: start the route from where the phone last knew it was,
            // so the miles driven before the first fix still count
            Location lk = lastKnown();
            if (lk != null && now - lk.getTime() < 10 * 60_000L && (!lk.hasAccuracy() || lk.getAccuracy() < 150f)) wakeFrom = lk;
            drive();
            handler.postDelayed(nag, 60_000L);
        }
        String act = intent == null ? null : intent.getAction();
        if (ACTION_WAKE.equals(act) && parked) wakeUp();       // the car connected again
        else if (ACTION_PARK.equals(act) && !parked) park();   // the car disconnected: you've stopped
        WidgetProvider.refresh(this);
        return START_STICKY;
    }

    // Reminders while logging: sort an automatic trip, and a trip left open for a week.
    private final Runnable nag = new Runnable() {
        @Override public void run() {
            SharedPreferences p = prefs(TrackingService.this);
            long now = System.currentTimeMillis(), since = p.getLong("since", now);
            if (!p.getBoolean("tripOpen", false) && now - since > 3_600_000L && now - p.getLong("nagSort", 0) > 6 * 3_600_000L) {
                p.edit().putLong("nagSort", now).apply();
                ReminderReceiver.notify(TrackingService.this, ReminderReceiver.ID_OPEN, "Trip logging, not sorted yet",
                        "Open DrivR and mark it Business or Private.", "open", false);
            }
            if (now - since > 7 * 86_400_000L && now - p.getLong("nagOpen", 0) > 86_400_000L) {
                p.edit().putLong("nagOpen", now).apply();
                ReminderReceiver.notify(TrackingService.this, ReminderReceiver.ID_OPEN, "Trip open for " + ((now - since) / 86_400_000L) + " days",
                        "If you're back, open DrivR and finish it.", "finish", false);
            }
            handler.postDelayed(this, 3_600_000L);
        }
    };

    // ---------- modes ----------
    private void drive() {
        parked = false;
        if (handler != null) handler.removeCallbacks(slowDown);
        if (handler != null) handler.removeCallbacks(parkCheck);
        stopAll();
        try {
            lm.requestLocationUpdates(driveProvider(), 2000L, 0f, gps, Looper.getMainLooper());
        } catch (SecurityException | IllegalArgumentException e) { /* no GPS */ }
        handler.postDelayed(parkCheck, 30_000L);
        mode("driving");
    }

    /** GPS when the device has it and precise location is allowed; otherwise Android's combined source. */
    private String driveProvider() {
        boolean fine = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        java.util.List<String> all = lm.getAllProviders();
        if (fine && all.contains(LocationManager.GPS_PROVIDER)) return LocationManager.GPS_PROVIDER;
        if (Build.VERSION.SDK_INT >= 31 && all.contains(LocationManager.FUSED_PROVIDER)) return LocationManager.FUSED_PROVIDER;
        return LocationManager.NETWORK_PROVIDER;
    }

    private void park() {
        parked = true;
        anchor = last != null ? last : lastKnown();
        last = null;
        stopAll();
        parkChecks(PARK_GPS_MS);
        handler.removeCallbacks(slowDown);
        handler.postDelayed(slowDown, 30 * 60_000L); // after half an hour parked, check less often
        try {
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, NET_EVERY_MS, 0f, net, Looper.getMainLooper());
        } catch (SecurityException | IllegalArgumentException e) { /* no network location */ }
        if (motion != null) {
            try { sm.requestTriggerSensor(wake, motion); } catch (Exception e) { /* ignore */ }
        }
        mode("parked");
    }

    private void parkChecks(long every) { // a GPS check every so often while parked
        try { lm.removeUpdates(parkGps); } catch (Exception e) { /* ignore */ }
        try { lm.requestLocationUpdates(driveProvider(), every, 0f, parkGps, Looper.getMainLooper()); }
        catch (SecurityException | IllegalArgumentException e) { /* no GPS */ }
    }
    private final Runnable slowDown = () -> { if (parked) parkChecks(5 * 60_000L); };

    private Location lastKnown() {
        Location best = null;
        try { for (String pr : lm.getProviders(true)) { Location l = lm.getLastKnownLocation(pr);
            if (l != null && (best == null || l.getTime() > best.getTime())) best = l; } } catch (SecurityException | IllegalArgumentException e) { /* none */ }
        return best;
    }

    private void wakeUp() {
        if (!parked) return;
        wakeFrom = anchor; // to add the distance covered before GPS was back on
        long now = System.currentTimeMillis();
        lastMoving = now - PARK_AFTER_MS + WAKE_GRACE_MS; // must reach driving speed within 2 min or it parks again
        last = null;
        drive();
    }

    private void stopAll() {
        try { lm.removeUpdates(gps); } catch (Exception e) { /* ignore */ }
        try { lm.removeUpdates(net); } catch (Exception e) { /* ignore */ }
        try { lm.removeUpdates(parkGps); } catch (Exception e) { /* ignore */ }
        if (motion != null) { try { sm.cancelTriggerSensor(wake, motion); } catch (Exception e) { /* ignore */ } }
    }

    // In case GPS gives no fixes at all (indoors, car park), still drop to parked mode.
    private final Runnable parkCheck = new Runnable() {
        @Override public void run() {
            if (parked) return;
            if (System.currentTimeMillis() - lastMoving > PARK_AFTER_MS) park();
            else handler.postDelayed(this, 30_000L);
        }
    };

    // ---------- listeners ----------
    private final LocationListener gps = new Listener() {
        @Override public void onLocationChanged(Location l) {
            if (parked || !l.hasAccuracy() || l.getAccuracy() > MAX_ACCURACY_M) return;
            long now = System.currentTimeMillis();
            if (last == null && wakeFrom != null) { // first fix after waking: count the miles driven before GPS caught up
                float gap = l.distanceTo(wakeFrom);
                if (gap > MOVED_M && gap < 50_000f) {
                    routePoint(wakeFrom); routePoint(l);
                    meters += gap * ROAD_FACTOR; lastMoving = now; lastDriving = now;
                    prefs(TrackingService.this).edit().putLong("meters", Double.doubleToLongBits(meters)).apply();
                    refreshNote();
                }
                wakeFrom = null;
            }
            if (last != null) {
                float d = l.distanceTo(last);
                double dt = Math.max(1.0, (l.getTime() - last.getTime()) / 1000.0);
                double v = l.hasSpeed() ? l.getSpeed() : d / dt;
                if (d / dt > 70) { last = l; return; } // a jump GPS can't have driven: skip it
                if (v >= DRIVING_MS) lastDriving = now;
                boolean inDrive = now - lastDriving < DRIVE_SPELL_MS;
                if (v >= DRIVING_MS || (inDrive && v >= CREEP_MS)) {
                    if (routeLast == null) routePoint(last);
                    routePoint(l);
                    meters += d;
                    lastMoving = now;
                    prefs(TrackingService.this).edit().putLong("meters", Double.doubleToLongBits(meters)).apply();
                    refreshNote();
                }
            }
            last = l;
            if (now - lastMoving > PARK_AFTER_MS) park();
        }
    };

    private final LocationListener net = new Listener() {
        @Override public void onLocationChanged(Location l) {
            if (!parked || !l.hasAccuracy() || l.getAccuracy() > 1500) return;
            if (anchor == null) { anchor = l; return; }
            float limit = Math.max(300f, l.getAccuracy() + anchor.getAccuracy());
            if (l.distanceTo(anchor) > limit) wakeUp();
        }
    };

    // parked: wake when a GPS check shows you've moved away or are moving at driving speed
    private final LocationListener parkGps = new Listener() {
        @Override public void onLocationChanged(Location l) {
            if (!parked || !l.hasAccuracy() || l.getAccuracy() > 100) return;
            if (anchor == null) { anchor = l; return; }
            boolean fast = l.hasSpeed() && l.getSpeed() >= DRIVING_MS;
            if (fast || l.distanceTo(anchor) > Math.max(MOVED_M, l.getAccuracy() * 2)) wakeUp();
        }
    };

    private final TriggerEventListener wake = new TriggerEventListener() {
        @Override public void onTrigger(TriggerEvent event) {
            if (handler != null) handler.post(TrackingService.this::wakeUp);
        }
    };

    /** Implements every LocationListener method so older Android versions don't trip over missing ones. */
    private abstract static class Listener implements LocationListener {
        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
        @Override public void onProviderEnabled(String provider) { }
        @Override public void onProviderDisabled(String provider) { }
    }

    // ---------- notification ----------
    private void mode(String m) {
        prefs(this).edit().putString("mode", m).apply();
        notedMiles = -1;
        refreshNote();
    }

    private void makeChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Trip logging", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shown while DrivR is logging a trip");
            ch.setShowBadge(false);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification note() {
        String m = prefs(this).getString("mode", "driving");
        double miles = meters / 1609.344;
        String text = String.format(Locale.UK, "%.1f mi so far", miles)
                + ("parked".equals(m) ? " · parked, GPS off" : " · logging your drive");
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = open == null ? null : PendingIntent.getActivity(this, 0, open, flags);
        SharedPreferences p = prefs(this);
        String label = p.getString("label", "");
        int expected = p.getInt("expectedMi", 0); // round trip, when the app knows it
        if (expected > 0) text = String.format(Locale.UK, "%.0f of %d mi expected", miles, expected) + ("parked".equals(m) ? " · parked" : "");
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(label.isEmpty() ? "Trip open" : label)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setColor(0xFFFF6A1A)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        if (expected > 0) b.setProgress(expected, (int) Math.min(expected, Math.round(miles)), false);
        if (p.getBoolean("tripOpen", false)) b.addAction(0, "Finish", ReminderReceiver.openApp(this, "finish", 8104));
        else b.addAction(0, "Sort trip", ReminderReceiver.openApp(this, "open", 8105));
        if (pi != null) b.setContentIntent(pi);
        return b.build();
    }

    private void refreshNote() {
        double miles = Math.floor(meters / 1609.344 * 10) / 10;
        if (miles == notedMiles) return;
        notedMiles = miles;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) { try { nm.notify(NOTE_ID, note()); } catch (Exception e) { /* ignore */ } }
        WidgetProvider.refresh(this);
    }

    @Override public void onDestroy() {
        running = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (lm != null) stopAll();
        WidgetProvider.refresh(this);
        super.onDestroy();
    }
}
