package uk.mileagebase.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@CapacitorPlugin(
    name = "Tracker",
    permissions = {
        @Permission(alias = "location", strings = { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION }),
        @Permission(alias = "notifications", strings = { "android.permission.POST_NOTIFICATIONS" })
    }
)
public class TrackerPlugin extends Plugin {

    private boolean locationOk() { return getPermissionState("location") == PermissionState.GRANTED; }

    @PluginMethod
    public void permissions(PluginCall call) {
        List<String> need = new ArrayList<>();
        if (!locationOk()) need.add("location");
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notifications") != PermissionState.GRANTED) need.add("notifications");
        if (need.isEmpty()) { permsDone(call); return; }
        requestPermissionForAliases(need.toArray(new String[0]), call, "permsDone");
    }

    @PermissionCallback
    private void permsDone(PluginCall call) {
        JSObject r = new JSObject();
        r.put("location", locationOk());
        call.resolve(r);
    }

    @PluginMethod
    public void start(PluginCall call) {
        if (!locationOk()) { call.reject("Location permission is off", "no_permission"); return; }
        Context c = getContext();
        boolean reset = Boolean.TRUE.equals(call.getBoolean("reset", true));
        SharedPreferences.Editor e = TrackingService.prefs(c).edit().putBoolean("active", true).putString("mode", "starting");
        if (reset) e.putLong("meters", Double.doubleToLongBits(0));
        e.apply();
        try {
            ContextCompat.startForegroundService(c, new Intent(c, TrackingService.class));
        } catch (Exception ex) {
            call.reject("Could not start trip logging: " + ex.getMessage(), "start_failed");
            return;
        }
        call.resolve(status(c));
    }

    @PluginMethod
    public void stop(PluginCall call) {
        Context c = getContext();
        JSObject r = status(c);
        TrackingService.prefs(c).edit().putBoolean("active", false).putString("mode", "stopped").apply();
        c.stopService(new Intent(c, TrackingService.class));
        call.resolve(r);
    }

    @PluginMethod
    public void status(PluginCall call) { call.resolve(status(getContext())); }

    private JSObject status(Context c) {
        SharedPreferences p = TrackingService.prefs(c);
        JSObject r = new JSObject();
        r.put("active", p.getBoolean("active", false));
        r.put("meters", TrackingService.readMeters(c));
        r.put("mode", p.getString("mode", "stopped"));
        r.put("location", locationOk());
        return r;
    }

    /** One position now: a recent saved fix if good enough, otherwise a fresh one (20 s limit). */
    @PluginMethod
    public void getLocation(PluginCall call) {
        if (!locationOk()) { call.reject("Location permission is off", "no_permission"); return; }
        final LocationManager lm = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
        long now = System.currentTimeMillis();
        try {
            for (String prov : new String[] { LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER }) {
                Location l = lm.getLastKnownLocation(prov);
                if (l != null && now - l.getTime() < 120_000L && l.hasAccuracy() && l.getAccuracy() < 100) { call.resolve(fix(l)); return; }
            }
        } catch (SecurityException | IllegalArgumentException e) { /* fall through */ }
        final AtomicBoolean done = new AtomicBoolean(false);
        final Handler h = new Handler(Looper.getMainLooper());
        final Location[] best = { null };
        final LocationListener ll = new LocationListener() {
            @Override public void onLocationChanged(Location l) {
                if (best[0] == null || (l.hasAccuracy() && l.getAccuracy() < best[0].getAccuracy())) best[0] = l;
                if (l.hasAccuracy() && l.getAccuracy() < 50 && done.compareAndSet(false, true)) {
                    try { lm.removeUpdates(this); } catch (Exception e) { /* ignore */ }
                    call.resolve(fix(l));
                }
            }
            @Override public void onStatusChanged(String p, int s, Bundle b) { }
            @Override public void onProviderEnabled(String p) { }
            @Override public void onProviderDisabled(String p) { }
        };
        h.post(() -> {
            try {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, ll, Looper.getMainLooper());
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, ll, Looper.getMainLooper());
            } catch (SecurityException | IllegalArgumentException e) { /* handled by the timeout */ }
        });
        h.postDelayed(() -> {
            if (!done.compareAndSet(false, true)) return;
            try { lm.removeUpdates(ll); } catch (Exception e) { /* ignore */ }
            if (best[0] != null) call.resolve(fix(best[0]));
            else call.reject("No location found. Check location is switched on.", "no_fix");
        }, 20_000L);
    }

    private JSObject fix(Location l) {
        JSObject r = new JSObject();
        r.put("lat", l.getLatitude());
        r.put("lon", l.getLongitude());
        r.put("accuracy", l.hasAccuracy() ? l.getAccuracy() : -1);
        return r;
    }

    @PluginMethod
    public void batteryOk(PluginCall call) {
        JSObject r = new JSObject();
        boolean ok = true;
        if (Build.VERSION.SDK_INT >= 23) {
            PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
            ok = pm != null && pm.isIgnoringBatteryOptimizations(getContext().getPackageName());
        }
        r.put("ok", ok);
        call.resolve(r);
    }

    @PluginMethod
    public void openBattery(PluginCall call) {
        Context c = getContext();
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
            } catch (Exception e2) { call.reject("Could not open settings", "no_settings"); return; }
        }
        call.resolve();
    }
}
