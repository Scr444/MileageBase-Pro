package uk.mileagebase.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
        @Permission(alias = "bluetooth", strings = { "android.permission.BLUETOOTH_CONNECT" }),
        @Permission(alias = "location", strings = { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION }),
        @Permission(alias = "notifications", strings = { "android.permission.POST_NOTIFICATIONS" })
    }
)
public class TrackerPlugin extends Plugin {

    @Override
    public void load() { ReminderReceiver.schedule(getContext()); }

    /** A tap on the widget or a reminder, waiting to be handled by the app. */
    @PluginMethod
    public void takeAction(PluginCall call) {
        SharedPreferences p = TrackingService.prefs(getContext());
        JSObject r = new JSObject();
        r.put("action", p.getString("pendingAction", ""));
        r.put("auto", p.getBoolean("auto", false));
        r.put("since", p.getLong("since", 0L));
        p.edit().remove("pendingAction").apply();
        call.resolve(r);
    }

    /** What the widget and reminders should know about the app's open trip. */
    @PluginMethod
    public void sync(PluginCall call) {
        boolean open = Boolean.TRUE.equals(call.getBoolean("tripOpen", false));
        SharedPreferences.Editor e = TrackingService.prefs(getContext()).edit()
                .putBoolean("tripOpen", open).putString("label", call.getString("label", ""))
                .putInt("expectedMi", call.getInt("expectedMi", 0)).putString("weekText", call.getString("weekText", ""));
        if (open) e.putBoolean("auto", false);
        e.apply();
        WidgetProvider.refresh(getContext());
        if (TrackingService.prefs(getContext()).getBoolean("active", false)) {
            try { ContextCompat.startForegroundService(getContext(), new Intent(getContext(), TrackingService.class)); } catch (Exception ex) { /* refreshed next time */ }
        }
        call.resolve();
    }

    private boolean btOk() { return Build.VERSION.SDK_INT < 31 || getPermissionState("bluetooth") == PermissionState.GRANTED; }

    @PluginMethod
    public void btPermission(PluginCall call) {
        if (btOk()) { btPermDone(call); return; }
        requestPermissionForAlias("bluetooth", call, "btPermDone");
    }

    @PermissionCallback
    private void btPermDone(PluginCall call) { JSObject r = new JSObject(); r.put("bluetooth", btOk()); call.resolve(r); }

    /** The phone's paired Bluetooth devices, cars first, and which are connected right now. */
    @PluginMethod
    public void pairedDevices(PluginCall call) {
        if (!btOk()) { call.reject("Nearby devices permission is off", "no_permission"); return; }
        JSObject r = new JSObject();
        com.getcapacitor.JSArray list = new com.getcapacitor.JSArray();
        try {
            android.bluetooth.BluetoothManager bm = (android.bluetooth.BluetoothManager) getContext().getSystemService(Context.BLUETOOTH_SERVICE);
            android.bluetooth.BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
            if (ad == null) { call.reject("This phone has no Bluetooth", "no_bt"); return; }
            r.put("on", ad.isEnabled());
            for (android.bluetooth.BluetoothDevice d : ad.getBondedDevices()) {
                JSObject o = new JSObject();
                o.put("name", d.getName() == null ? d.getAddress() : d.getName());
                o.put("address", d.getAddress());
                o.put("car", ReminderReceiver.looksLikeCar(d));
                boolean conn = false;
                try { conn = (Boolean) d.getClass().getMethod("isConnected").invoke(d); } catch (Exception e) { /* unknown */ }
                o.put("connected", conn);
                list.put(o);
            }
        } catch (SecurityException e) { call.reject("Nearby devices permission is off", "no_permission"); return; }
        r.put("devices", list);
        call.resolve(r);
    }

    @PluginMethod
    public void setCars(PluginCall call) {
        TrackingService.prefs(getContext()).edit().putString("cars", call.getString("cars", "")).apply();
        call.resolve();
    }

    @PluginMethod
    public void cars(PluginCall call) {
        SharedPreferences p = TrackingService.prefs(getContext());
        String cars = p.getString("cars", "");
        JSObject r = new JSObject();
        r.put("cars", cars);
        r.put("lastCar", Math.max(p.getLong("carIn", 0), p.getLong("carOut", 0)));
        r.put("bluetooth", btOk());
        r.put("background", backgroundOk());
        call.resolve(r);
    }

    @PluginMethod
    public void forgetCars(PluginCall call) {
        TrackingService.prefs(getContext()).edit().putString("cars", "").apply();
        call.resolve();
    }

    private boolean backgroundOk() {
        if (Build.VERSION.SDK_INT < 29) return locationOk();
        return ContextCompat.checkSelfPermission(getContext(), "android.permission.ACCESS_BACKGROUND_LOCATION") == PackageManager.PERMISSION_GRANTED;
    }

    @PluginMethod
    public void openAppSettings(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) { call.reject("Could not open settings", "no_settings"); }
    }

    /** Hands the site postcode to whatever satnav app is installed. */
    @PluginMethod
    public void openNav(PluginCall call) {
        String q = call.getString("q", "");
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q)));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(Intent.createChooser(i, "Navigate with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            call.resolve();
        } catch (Exception e) { call.reject("No satnav app found", "no_app"); }
    }

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
        if (reset) { e.putLong("meters", Double.doubleToLongBits(0)).putLong("since", System.currentTimeMillis()).putBoolean("auto", false); TrackingService.routeFile(c).delete(); }
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

    /** The route so far, as "lat,lon" lines. */
    @PluginMethod
    public void route(PluginCall call) {
        JSObject r = new JSObject();
        String pts = "";
        try {
            java.io.File f = TrackingService.routeFile(getContext());
            if (f.exists()) {
                byte[] b = new byte[(int) f.length()];
                try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                    int off = 0, n;
                    while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
                }
                pts = new String(b, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) { /* empty route */ }
        r.put("points", pts);
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

    private boolean precise() {
        return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** One position now: the best recent saved fix, otherwise a fresh one from every live source (30 s limit). */
    @PluginMethod
    public void getLocation(PluginCall call) {
        if (!locationOk()) { call.reject("Location permission is off", "no_permission"); return; }
        final LocationManager lm = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) { call.reject("This device has no location service", "no_fix"); return; }
        final List<String> live = new ArrayList<>();
        try { for (String pr : lm.getProviders(true)) if (!LocationManager.PASSIVE_PROVIDER.equals(pr)) live.add(pr); } catch (Exception e) { /* none */ }
        long now = System.currentTimeMillis();
        Location saved = null;
        try {
            for (String pr : lm.getAllProviders()) {
                Location l = lm.getLastKnownLocation(pr);
                if (l == null || now - l.getTime() > 600_000L || (l.hasAccuracy() && l.getAccuracy() > 1000)) continue;
                if (saved == null || (l.hasAccuracy() && l.getAccuracy() < saved.getAccuracy())) saved = l;
            }
        } catch (SecurityException | IllegalArgumentException e) { /* fall through */ }
        if (saved != null && saved.hasAccuracy() && saved.getAccuracy() < 200) { call.resolve(fix(saved)); return; }
        if (live.isEmpty()) {
            if (saved != null) { call.resolve(fix(saved)); return; }
            call.reject("Location is switched off in the phone's settings.", "no_fix");
            return;
        }
        final Location fallback = saved;
        final AtomicBoolean done = new AtomicBoolean(false);
        final Handler h = new Handler(Looper.getMainLooper());
        final Location[] best = { null };
        final LocationListener ll = new LocationListener() {
            @Override public void onLocationChanged(Location l) {
                if (best[0] == null || (l.hasAccuracy() && l.getAccuracy() < best[0].getAccuracy())) best[0] = l;
                if (l.hasAccuracy() && l.getAccuracy() < 100 && done.compareAndSet(false, true)) {
                    try { lm.removeUpdates(this); } catch (Exception e) { /* ignore */ }
                    call.resolve(fix(l));
                }
            }
            @Override public void onStatusChanged(String p, int st, Bundle b) { }
            @Override public void onProviderEnabled(String p) { }
            @Override public void onProviderDisabled(String p) { }
        };
        h.post(() -> {
            for (String pr : live) {
                try { lm.requestLocationUpdates(pr, 1000L, 0f, ll, Looper.getMainLooper()); }
                catch (SecurityException | IllegalArgumentException e) { /* skip this source */ }
            }
        });
        h.postDelayed(() -> {
            if (!done.compareAndSet(false, true)) return;
            try { lm.removeUpdates(ll); } catch (Exception e) { /* ignore */ }
            if (best[0] != null) call.resolve(fix(best[0]));
            else if (fallback != null) call.resolve(fix(fallback));
            else call.reject("No fix after 30 s (sources: " + android.text.TextUtils.join(", ", live) + (precise() ? "" : "; precise location is OFF for this app") + "). Try by a window or outside.", "no_fix");
        }, 30_000L);
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
