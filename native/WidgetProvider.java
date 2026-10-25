package uk.mileagebase.app;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.RemoteViews;
import java.util.Locale;

/** Home-screen widget: trip status and miles, with Business / Private / Finish buttons. */
public class WidgetProvider extends AppWidgetProvider {

    static void refresh(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, WidgetProvider.class));
            if (ids != null && ids.length > 0) draw(c, m, ids);
        } catch (Exception e) { /* no widget */ }
    }

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) { draw(c, m, ids); }

    static void draw(Context c, AppWidgetManager m, int[] ids) {
        SharedPreferences p = TrackingService.prefs(c);
        boolean open = p.getBoolean("tripOpen", false);
        String label = p.getString("label", "");
        double miles = TrackingService.readMeters(c) / 1609.344;
        boolean logging = p.getBoolean("active", false);
        for (int id : ids) {
            RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.mbp_widget);
            v.setTextViewText(R.id.w_title, open ? (label.isEmpty() ? "Trip open" : label) : "No trip open");
            v.setTextViewText(R.id.w_miles, open && logging ? String.format(Locale.UK, "%.1f mi", miles) : (open ? "Trip open" : "Tap to start"));
            v.setViewVisibility(R.id.w_start_row, open ? View.GONE : View.VISIBLE);
            v.setViewVisibility(R.id.w_finish, open ? View.VISIBLE : View.GONE);
            v.setOnClickPendingIntent(R.id.w_root, ReminderReceiver.openApp(c, "open", 8001));
            v.setOnClickPendingIntent(R.id.w_bus, ReminderReceiver.openApp(c, "business", 8002));
            v.setOnClickPendingIntent(R.id.w_priv, ReminderReceiver.openApp(c, "private", 8003));
            v.setOnClickPendingIntent(R.id.w_finish, ReminderReceiver.openApp(c, "finish", 8004));
            m.updateAppWidget(id, v);
        }
    }
}
