package tv.lighthouse.launcher;

import android.graphics.Bitmap;

public class AppEntry {
    public String pkg;
    public String label;
    public Bitmap icon;
    public int color = 0xFF8A6FD8;
    public int launches;
    public long lastUsed;

    /** 0 = fresh, 1 = long unused. Drives the tile's brightness. */
    public float fade() {
        if (lastUsed == 0) return 0.55f;
        long days = (System.currentTimeMillis() - lastUsed) / (1000L * 60 * 60 * 24);
        if (days <= 2) return 1.0f;
        if (days >= 45) return 0.45f;
        return 1.0f - (days - 2) * 0.55f / 43f;
    }
}
