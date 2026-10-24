package tv.lighthouse.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AppRepository {

    private static final int ICON_PX = 160;
    private final Context ctx;
    private final SharedPreferences prefs;

    public AppRepository(Context c) {
        ctx = c.getApplicationContext();
        prefs = ctx.getSharedPreferences("lighthouse_tv_usage", Context.MODE_PRIVATE);
    }

    // Not worth a petal on a television wheel.
    private static final String[] HIDDEN = {
            "com.android.gallery3d",
            "com.android.camera2",
            "com.android.printspooler",
            "org.chromium.webview_shell",
            "com.gathin.tvhome",
    };

    public List<AppEntry> load() {
        PackageManager pm = ctx.getPackageManager();
        List<ResolveInfo> all = new ArrayList<>();
        Intent leanback = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        all.addAll(pm.queryIntentActivities(leanback, 0));
        Intent plain = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        all.addAll(pm.queryIntentActivities(plain, 0));

        Set<String> seen = new HashSet<>();
        List<AppEntry> out = new ArrayList<>();
        for (ResolveInfo ri : all) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName())) continue;
            if (java.util.Arrays.asList(HIDDEN).contains(pkg)) continue;
            if (!seen.add(pkg)) continue;
            // A disabled package still answers intents here; it must not appear on the wheel.
            // ApplicationInfo.enabled is the manifest state only, so ask for the user-level one too.
            try {
                int state = pm.getApplicationEnabledSetting(pkg);
                if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                        || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) continue;
                if (!pm.getApplicationInfo(pkg, 0).enabled) continue;
                // ApplicationInfo.CATEGORY_HOME == 2: other launchers are not apps you open from here.
                if (pm.getApplicationInfo(pkg, 0).category == 2) continue;
            } catch (Exception e) {
                continue;
            }
            if (intentFor(pkg) == null) continue;
            AppEntry e = new AppEntry();
            e.pkg = pkg;
            e.label = ri.loadLabel(pm).toString();
            e.icon = toBitmap(ri.loadIcon(pm));
            e.color = dominantColor(e.icon);
            e.launches = prefs.getInt("n:" + pkg, 0);
            e.lastUsed = prefs.getLong("t:" + pkg, 0L);
            out.add(e);
        }

        // Alphabetical, and never re-sorted after a launch: a wheel that rearranges itself under
        // the finger is disorienting, and it made the tile you were on jump to the top.
        Collections.sort(out, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return out;
    }

    public void recordLaunch(String pkg) {
        int n = prefs.getInt("n:" + pkg, 0) + 1;
        prefs.edit()
                .putInt("n:" + pkg, n)
                .putLong("t:" + pkg, System.currentTimeMillis())
                .apply();
    }

    public Intent intentFor(String pkg) {
        PackageManager pm = ctx.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return null;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }

    private Bitmap toBitmap(Drawable d) {
        Bitmap b = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, ICON_PX, ICON_PX);
        d.draw(c);
        return b;
    }

    /** Average of the vivid pixels, so a glow can take the app's own colour. */
    private int dominantColor(Bitmap b) {
        long r = 0, g = 0, bl = 0, n = 0;
        for (int y = 0; y < b.getHeight(); y += 6) {
            for (int x = 0; x < b.getWidth(); x += 6) {
                int p = b.getPixel(x, y);
                int a = (p >>> 24);
                if (a < 200) continue;
                int rr = (p >> 16) & 0xFF, gg = (p >> 8) & 0xFF, bb = p & 0xFF;
                int max = Math.max(rr, Math.max(gg, bb));
                int min = Math.min(rr, Math.min(gg, bb));
                int sat = max - min;
                if (sat < 40) continue;
                r += rr; g += gg; bl += bb; n++;
            }
        }
        if (n == 0) return 0xFF8A6FD8;
        int rr = (int) (r / n), gg = (int) (g / n), bb = (int) (bl / n);
        // Lift the colour so it reads as light, not mud.
        int max = Math.max(rr, Math.max(gg, bb));
        if (max < 90) {
            float k = 90f / Math.max(1, max);
            rr = Math.min(255, (int) (rr * k));
            gg = Math.min(255, (int) (gg * k));
            bb = Math.min(255, (int) (bb * k));
        }
        return 0xFF000000 | (rr << 16) | (gg << 8) | bb;
    }
}
