package tv.lighthouse.launcher;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class LightHouseView extends View {

    private static final float STEP = 0.30f;

    private final AppRepository repo;
    private final SoundEngine sound;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Typeface font;

    private final List<AppEntry> apps = new ArrayList<>();
    private int selected = 0;

    private float position = 0f, positionVel = 0f, targetPosition = 0f;
    private float entrance = 0f, nameAlpha = 1f, launchT = 0f, navAlpha = 0f;
    private float[] tileScale = new float[0];
    private float nameSize;

    private int navIndex = 2;
    private boolean navFocused = false;

    private float arcCx, arcCy, arcR, tile;
    private float lanternX, lanternY;

    private Bitmap bg;
    private Bitmap beamSprite;
    // The beam is not simulated. Its angle is the direction from the lamp to the focused tile,
    // which is a pure function of the wheel position, so the light can only move while the wheel
    // moves. There is no aim point and no second integrator: those two chained lags were what let
    // the beam wander on its own even when nothing was pressed.
    private float beamAngle = 0f;
    private boolean beamSnap = true;
    private Bitmap overlay;
    private Shader moonGlowNav;
    private int lastClockMinute = -1;

    private Shader lampGlow;
    private Shader nameShader;
    private float nameShaderLit = -999f;
    private float nameShaderSize = -1f;
    private Shader tileGlowShader;
    private int tileGlowColor = 0;
    private float tileGlowSize = -1f;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint scrim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vignettePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint beamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint beamCore = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint clockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint datePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint namePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint navDisc = new Paint(Paint.ANTI_ALIAS_FLAG);

    private Shader vignette;
    private Shader rightScrim;

    private final Path beamPath = new Path();
    private final RectF drawRect = new RectF();
    private final Rect srcRect = new Rect();

    private final SimpleDateFormat clockFmt = new SimpleDateFormat("HH:mm", Locale.ENGLISH);
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE, d MMMM", Locale.ENGLISH);

    private static final Map<String, String> SUBTITLES = new HashMap<>();

    static {
        SUBTITLES.put("com.android.tv", "Watch channels and discover");
        SUBTITLES.put("com.amazon.amazonvideo.livingroom", "Films and series");
        SUBTITLES.put("com.google.android.youtube.tv", "Videos and music");
        SUBTITLES.put("com.google.android.youtube.tvmusic", "Concerts and albums");
        SUBTITLES.put("in.startv.hotstar", "Live sport and shows");
        SUBTITLES.put("com.sonyliv", "Shows and live TV");
        SUBTITLES.put("com.zee5.xiaomiTV", "Shows and movies");
        SUBTITLES.put("org.videolan.vlc", "Play from a drive");
        SUBTITLES.put("com.android.vending", "Apps and games");
        SUBTITLES.put("com.xiaomi.mitv.mediaexplorer", "Browse your files");
    }

    public LightHouseView(Context c, AppRepository repo, SoundEngine sound) {
        super(c);
        this.repo = repo;
        this.sound = sound;
        setFocusable(true);
        setFocusableInTouchMode(true);

        Typeface f;
        try {
            f = getResources().getFont(R.font.outfit);
        } catch (Exception e) {
            f = Typeface.create("sans-serif-light", Typeface.NORMAL);
        }
        font = f;

        clockPaint.setTypeface(font);
        clockPaint.setColor(0xFFFFFFFF);
        clockPaint.setTextAlign(Paint.Align.RIGHT);
        datePaint.setTypeface(font);
        datePaint.setColor(0xFFC9C0B2);
        datePaint.setLetterSpacing(0.30f);
        datePaint.setTextAlign(Paint.Align.RIGHT);

        namePaint.setTypeface(Typeface.create(font, Typeface.BOLD));
        namePaint.setColor(0xFFFFFFFF);
        namePaint.setLetterSpacing(0.02f);

        subPaint.setTypeface(font);
        subPaint.setColor(0xFFCFC6B8);
        subPaint.setLetterSpacing(0.05f);

        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
        navDisc.setStyle(Paint.Style.FILL);

        borderPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStyle(Paint.Style.STROKE);

    }

    public void reloadApps() {
        List<AppEntry> loaded = repo.load();
        apps.clear();
        apps.addAll(loaded);
        if (selected >= apps.size()) selected = 0;
        if (tileScale.length != apps.size()) tileScale = new float[apps.size()];
        for (int i = 0; i < tileScale.length; i++) tileScale[i] = 0.9f;
        entrance = 0f;
        nameAlpha = 0f;
        position = selected;
        targetPosition = selected;
        positionVel = 0f;
        beamSnap = true;
        layout();
        buildCards();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float min = Math.min(w, h);

        lanternX = w * 0.1145f;
        lanternY = h * 0.352f;

        clockPaint.setTextSize(min * 0.100f);
        datePaint.setTextSize(min * 0.0205f);
        subPaint.setTextSize(min * 0.027f);

        vignette = new RadialGradient(w * 0.5f, h * 0.55f, Math.max(w, h) * 0.85f,
                new int[]{0x00000000, 0x00000000, 0x66000000, 0xD9000000},
                new float[]{0f, 0.55f, 0.88f, 1f}, Shader.TileMode.CLAMP);
        moonGlowNav = new RadialGradient(0, 0, h * 0.55f,
                new int[]{0x3A9FB6FF, 0x146F86D8, 0x00000000},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);

        lampGlow = new RadialGradient(lanternX, lanternY, h * 0.060f,
                new int[]{0x8CFFE9B8, 0x33FFC46A, 0x00000000},
                new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP);
        rightScrim = new LinearGradient(w * 0.40f, 0, w, 0,
                new int[]{0x00000000, 0x66000000, 0xB3000000},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);

        loadBackdrop(w, h);
        buildBeamSprite();
        buildOverlay(w, h);
        beamSnap = true;
        layout();
        buildCards();
    }

    private void loadBackdrop(int w, int h) {
        try {
            Bitmap raw = BitmapFactory.decodeResource(getResources(), R.drawable.bg_night);
            if (raw == null) return;
            float scale = Math.max((float) w / raw.getWidth(), (float) h / raw.getHeight());
            int cw = Math.round(raw.getWidth() * scale);
            int ch = Math.round(raw.getHeight() * scale);
            Bitmap scaled = Bitmap.createScaledBitmap(raw, cw, ch, true);
            bg = Bitmap.createBitmap(scaled, Math.max(0, (cw - w) / 2), Math.max(0, (ch - h) / 2), w, h);
        } catch (Exception | OutOfMemoryError ignored) {
        }
    }

    private void buildOverlay(int w, int h) {
        Bitmap o = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(o);
        Paint sc = new Paint(Paint.ANTI_ALIAS_FLAG);
        sc.setShader(rightScrim);
        c.drawRect(w * 0.40f, 0, w, h, sc);
        sc.setShader(vignette);
        c.drawRect(0, 0, w, h, sc);
        if (overlay != null) overlay.recycle();
        overlay = o;
    }

    /**
     * The beam is painted into a sprite once: a cone that is bright on its axis and fades out
     * sideways and toward the tip. Drawing it later is a single rotated, scaled bitmap, so the
     * falloff is perfectly smooth instead of reading as stacked shapes.
     */
    private void buildBeamSprite() {
        int W = 512, H = 256;
        float halfAtEnd = H * 0.25f;
        int[] px = new int[W * H];
        for (int y = 0; y < H; y++) {
            float dy = Math.abs(y - H * 0.5f);
            for (int x = 0; x < W; x++) {
                float t = x / (float) (W - 1);
                float halfW = 2f + halfAtEnd * t;
                float across = dy <= halfW ? 1f - (dy / halfW) * (dy / halfW) : 0f;
                across = across * across;
                float along = (float) Math.pow(1f - t, 1.5) * Math.min(1f, t * 10f);
                int alpha = (int) (255 * across * along);
                px[y * W + x] = alpha <= 0 ? 0 : (alpha << 24) | (0xFF << 16) | (0xC8 << 8) | 0x6A;
            }
        }
        beamSprite = Bitmap.createBitmap(px, W, H, Bitmap.Config.ARGB_8888);
    }

    private void layout() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        arcCx = w * 0.150f;
        arcCy = h * 0.500f;
        arcR = w * 0.290f;
        tile = h * 0.115f;

        float maxW = w * 0.335f;
        float probe = h * 0.135f;
        namePaint.setTextSize(probe);
        float widest = 1f;
        for (AppEntry e : apps) {
            widest = Math.max(widest, namePaint.measureText(e.label.toUpperCase(Locale.ENGLISH)));
        }
        nameSize = widest > maxW ? probe * (maxW / widest) : probe;

        // targetPosition is deliberately not folded back to a wrapped index here. It is the
        // unwrapped wheel position, and resetting it on a relayout jumped the wheel and the beam.
        beamSnap = true;
    }

    private void buildCards() {
        int size = Math.round(tile * 1.42f);
        if (size <= 0) return;
        for (AppEntry e : apps) {
            if (e.icon == null) continue;
            if (e.icon.getWidth() == size) continue;
            Bitmap card = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(card);
            Path p = new Path();
            float r = size * 0.235f;
            p.addRoundRect(new RectF(0, 0, size, size), r, r, Path.Direction.CW);
            c.save();
            c.clipPath(p);
            Rect s = new Rect(0, 0, e.icon.getWidth(), e.icon.getHeight());
            RectF d = new RectF(0, 0, size, size);
            c.drawBitmap(e.icon, s, d, cardPaint);
            Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
            sheen.setShader(new LinearGradient(0, 0, 0, size,
                    new int[]{0x26FFFFFF, 0x00FFFFFF, 0x26000000},
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(d, sheen);
            c.restore();
            e.icon = card;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long nowMs = System.currentTimeMillis();
        float now = nowMs / 1000f;
        int w = getWidth(), h = getHeight();
        float ease = (1f - (float) Math.pow(1f - entrance, 3f)) * (1f - navAlpha);

        drawBackdrop(canvas, now, w, h);
        drawBeam(canvas, now, w, h, ease);
        drawArc(canvas, now, w, h, ease);
        drawInfo(canvas, now, w, h, ease);
        drawClock(canvas, nowMs, w, h, ease);
        drawRail(canvas, w, h, ease);
    }

    private void drawBackdrop(Canvas canvas, float now, int w, int h) {
        if (bg == null) {
            canvas.drawColor(0xFF05070E);
            return;
        }

        float camS = 1f + 0.45f * navAlpha;
        float camDx = -(camS - 1f) * w * 0.45f;

        canvas.save();
        canvas.translate(camDx, 0);
        canvas.scale(camS, camS, w * 0.5f, h * 0.5f);
        canvas.drawBitmap(bg, 0, 0, bgPaint);
        canvas.restore();

        if (overlay != null) canvas.drawBitmap(overlay, 0, 0, bgPaint);

        if (navAlpha > 0.01f) {
            Paint dark = new Paint(Paint.ANTI_ALIAS_FLAG);
            dark.setColor(0xFF04050A);
            dark.setAlpha((int) (120 * navAlpha));
            canvas.drawRect(0, 0, w, h, dark);

            if (moonGlowNav != null) {
                float mx = (w * 0.665f - w * 0.5f) * camS + w * 0.5f + camDx;
                float my = (h * 0.145f - h * 0.5f) * camS + h * 0.5f;
                Paint moon = new Paint(Paint.ANTI_ALIAS_FLAG);
                moon.setShader(moonGlowNav);
                moon.setAlpha((int) (255 * navAlpha));
                canvas.save();
                canvas.translate(mx, my);
                canvas.drawCircle(0, 0, h * 0.55f, moon);
                canvas.restore();
                moon.setShader(null);
            }
        }
    }

    /**
     * Screen angle of the tile the wheel is carrying into the centre, measured in unwrapped wheel
     * space so that leaving the last app for the first is one step in one direction and never a
     * lap of the whole circle. At rest this is exactly 0: the focused tile sits on the seat.
     */
    private float focusedAngle() {
        if (apps.isEmpty()) return 0f;
        float steps = Math.round(targetPosition) - position;
        if (steps > 1f) steps = 1f;
        if (steps < -1f) steps = -1f;
        return steps * STEP;
    }

    private float focusedTileX() {
        return arcCx + (float) Math.cos(focusedAngle()) * arcR;
    }

    private float focusedTileY() {
        return arcCy + (float) Math.sin(focusedAngle()) * arcR;
    }

    private float nameRightEdge() {
        if (apps.isEmpty()) return getWidth() * 0.86f;
        String name = apps.get(selected).label.toUpperCase(Locale.ENGLISH);
        namePaint.setTextSize(nameSize > 0 ? nameSize : getHeight() * 0.135f);
        return getWidth() * 0.545f + namePaint.measureText(name);
    }

    private void drawBeam(Canvas canvas, float now, int w, int h, float ease) {
        float glow = ease * (1f - navAlpha);
        if (glow <= 0.01f || beamSprite == null) return;

        float a = beamAngle;
        float ux = (float) Math.cos(a);
        // The lamp is fixed to the left of the arc and the aim never leaves the arc, so the beam
        // always points rightwards; this only guards a degenerate angle.
        if (ux < 0.20f) return;

        // constant length, whatever the name is: the light always reaches the same place
        float targetX = w * 0.86f;
        float reach = (targetX - lanternX) / Math.abs(ux);
        if (reach < 60f) return;

        float tanA = (float) Math.tan(0.075f);
        float tipHalf = tanA * reach;

        float sw = beamSprite.getWidth();
        float sh = beamSprite.getHeight();
        float maxHalf = sh * 0.25f;

        canvas.save();
        canvas.translate(lanternX, lanternY);
        canvas.rotate((float) Math.toDegrees(a));
        canvas.scale(reach / sw, tipHalf / maxHalf);
        beamPaint.setAlpha((int) (145 * glow));
        canvas.drawBitmap(beamSprite, 0, -sh * 0.5f, beamPaint);
        canvas.restore();
        beamPaint.setAlpha(255);

        if (lampGlow != null) {
            Paint lamp = new Paint(Paint.ANTI_ALIAS_FLAG);
            lamp.setShader(lampGlow);
            lamp.setAlpha((int) (190 * glow));
            canvas.drawCircle(lanternX, lanternY, h * 0.060f, lamp);
            lamp.setShader(null);
        }
    }

    private void drawArc(Canvas canvas, float now, int w, int h, float ease) {
        int n = apps.size();
        if (n == 0) return;

        float a0 = (1f - navAlpha) * ease;
        if (a0 <= 0.01f) return;

        RectF arcRect = new RectF(arcCx - arcR, arcCy - arcR, arcCx + arcR, arcCy + arcR);

        // the guide line is drawn in short pieces whose alpha dies away at both ends
        int segs = 10;
        float span = 122f;
        float from = -61f;
        arcPaint.setStrokeWidth(Math.max(1f, tile * 0.0055f));
        for (int k = 0; k < segs; k++) {
            float t = k / (float) (segs - 1);
            float fade = (float) Math.sin(Math.PI * t);
            fade = fade * fade;
            if (fade < 0.01f) continue;

            arcPaint.setColor(0xFFFFE6C2);
            arcPaint.setAlpha((int) (20 * fade * a0));
            canvas.drawArc(arcRect, from + t * span, span / segs + 0.7f, false, arcPaint);

            if (k % 2 == 0) {
                arcPaint.setAlpha((int) (9 * fade * a0));
                arcPaint.setStrokeWidth(Math.max(3f, tile * 0.030f));
                canvas.drawArc(arcRect, from + t * span, span / segs + 0.7f, false, arcPaint);
                arcPaint.setStrokeWidth(Math.max(1f, tile * 0.0055f));
            }
        }

        // the apps themselves
        for (int i = 0; i < n; i++) {
            float d = position - i;
            while (d > n / 2f) d -= n;
            while (d < -n / 2f) d += n;

            float angle = -d * STEP;
            if (Math.abs(angle) > 1.30f) continue;

            float x = arcCx + (float) Math.cos(angle) * arcR;
            float y = arcCy + (float) Math.sin(angle) * arcR;

            if (Math.abs(d) < 0.5f) {
                drawTileCard(canvas, x, y, apps.get(i), true, 1f, ease);
            } else if (Math.abs(d) <= 2.6f) {
                drawTileCard(canvas, x, y, apps.get(i), false,
                        Math.max(0.15f, 1f - Math.abs(d) * 0.28f), ease);
            } else {
                drawStar(canvas, x, y, (int) Math.round(Math.abs(d)), now);
            }
        }
    }

    private void drawTileCard(Canvas canvas, float x, float y, AppEntry e, boolean focused,
                              float falloff, float ease) {
        float base = focused ? 1f : 0.30f + 0.60f * falloff;
        float s = (focused ? tileScale[Math.min(selected, tileScale.length - 1)] : 1f) * ease;
        if (focused) s *= 1f + launchT * 0.30f;
        float size = tile * base * s;
        if (size < 1.5f) return;
        float half = size * 0.5f;

        if (focused) {
            if (tileGlowSize < 0f || Math.abs(tileGlowSize - size) > 3f) {
                tileGlowShader = new RadialGradient(0, 0, size * 1.6f,
                        new int[]{0x66FFD79A, 0x22FFAE5A, 0x00000000},
                        new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
                tileGlowSize = size;
            }
            glowPaint.setShader(tileGlowShader);
            canvas.save();
            canvas.translate(x, y);
            canvas.drawCircle(0, 0, size * 1.6f, glowPaint);
            canvas.restore();
            glowPaint.setShader(null);
        }

        drawRect.set(x - half, y - half, x + half, y + half);
        float alpha = focused ? 1f : 0.35f + 0.5f * falloff;
        cardPaint.setAlpha((int) (255 * alpha * ease));
        canvas.drawBitmap(e.icon, null, drawRect, cardPaint);
        cardPaint.setAlpha(255);

        if (focused) {
            borderPaint.setStrokeWidth(Math.max(2f, size * 0.018f));
            borderPaint.setColor(0xFFFFD9A0);
            borderPaint.setAlpha(180);
            canvas.drawRoundRect(drawRect, size * 0.245f, size * 0.245f, borderPaint);
        }
    }

    private void drawStar(Canvas canvas, float x, float y, int distance, float now) {
        float a = Math.max(0.10f, 0.52f - distance * 0.08f);
        float r = Math.max(1.1f, tile * 0.028f * (1f - distance * 0.08f));
        float tw = 0.6f + 0.4f * Math.abs((float) Math.sin(now * 0.8f + distance));
        dotPaint.setColor(0xFFFFE2B4);
        dotPaint.setAlpha((int) (255 * a * tw));
        canvas.drawCircle(x, y, r, dotPaint);
    }

    private void drawInfo(Canvas canvas, float now, int w, int h, float ease) {
        if (apps.isEmpty()) return;
        float x = w * 0.545f;
        float baseY = h * 0.625f;
        float full = 1f - (float) Math.pow(1f - entrance, 3f);

        if (navAlpha < 0.99f) {
            AppEntry sel = apps.get(selected);
            String name = sel.label.toUpperCase(Locale.ENGLISH);
            float size = nameSize > 0 ? nameSize : h * 0.135f;
            namePaint.setTextSize(size);
            namePaint.setStyle(Paint.Style.FILL_AND_STROKE);
            namePaint.setStrokeWidth(size * 0.012f);
            namePaint.setStrokeJoin(Paint.Join.ROUND);

            float nameW = namePaint.measureText(name);

            // where the lantern's beam crosses the line of type: the letters light up there and
            // fall away into the dark either side, the way light behaves on water
            float nameMidY = baseY - size * 0.32f;
            float tx = focusedTileX();
            float ty = focusedTileY();
            float litX;
            if (Math.abs(ty - lanternY) > 4f) {
                float t = (nameMidY - lanternY) / (ty - lanternY);
                litX = lanternX + (tx - lanternX) * t;
            } else {
                litX = x + nameW * 0.5f;
            }
            litX = Math.max(x + nameW * 0.10f, Math.min(x + nameW * 0.90f, litX));
            float a = full * nameAlpha * (1f - navAlpha);

            namePaint.setShader(null);
            namePaint.setAlpha((int) (110 * a));
            canvas.drawText(name, x, baseY, namePaint);

            if (nameShader == null || Math.abs(litX - nameShaderLit) > 5f
                    || Math.abs(size - nameShaderSize) > 1f) {
                nameShader = new RadialGradient(litX, nameMidY, Math.max(2f, nameW * 0.58f),
                        new int[]{0xFFFFFFFF, 0xFFFFF2D8, 0xC6FFDFB0, 0x33FFFFFF, 0x14FFFFFF},
                        new float[]{0f, 0.34f, 0.60f, 0.86f, 1f}, Shader.TileMode.CLAMP);
                nameShaderLit = litX;
                nameShaderSize = size;
            }
            namePaint.setShader(nameShader);
            namePaint.setAlpha((int) (255 * a));
            canvas.drawText(name, x, baseY, namePaint);
            namePaint.setShader(null);

            float ruleY = baseY + h * 0.034f;
            Paint rule = new Paint(Paint.ANTI_ALIAS_FLAG);
            rule.setShader(new LinearGradient(x, ruleY, x + nameW * 0.55f, ruleY,
                    new int[]{0xFFFFD9A0, 0x00FFD9A0},
                    new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            rule.setStrokeWidth(2f);
            rule.setAlpha((int) (160 * a));
            canvas.drawLine(x, ruleY, x + nameW * 0.55f, ruleY, rule);

            String sub = SUBTITLES.containsKey(sel.pkg) ? SUBTITLES.get(sel.pkg) : "Open " + sel.label;
            subPaint.setAlpha((int) (190 * a));
            canvas.drawText(sub, x, ruleY + h * 0.058f, subPaint);
        }

        if (navAlpha > 0.01f) {
            String title = navIndex == 0 ? "WI-FI" : (navIndex == 1 ? "SETTINGS" : "INPUTS");
            String sub = navIndex == 0 ? wifiLabel()
                    : (navIndex == 1 ? "Picture, sound, network and apps"
                    : "HDMI 1  \u00B7  HDMI 2  \u00B7  HDMI 3  \u00B7  AV");

            float size = nameSize > 0 ? nameSize : h * 0.135f;
            namePaint.setTextSize(size);
            namePaint.setStyle(Paint.Style.FILL_AND_STROKE);
            namePaint.setStrokeWidth(size * 0.012f);
            float wName = namePaint.measureText(title);
            float a = navAlpha * full;

            namePaint.setShader(new LinearGradient(x, 0, x + Math.min(wName, w * 0.34f), 0,
                    new int[]{0xFFFFFFFF, 0xD9FFEFD2},
                    new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            namePaint.setAlpha((int) (255 * a));
            canvas.drawText(title, x, baseY, namePaint);
            namePaint.setShader(null);

            float ruleY = baseY + h * 0.034f;
            Paint rule = new Paint(Paint.ANTI_ALIAS_FLAG);
            rule.setShader(new LinearGradient(x, ruleY, x + wName * 0.55f, ruleY,
                    new int[]{0xFFBFD4FF, 0x00BFD4FF},
                    new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            rule.setStrokeWidth(2f);
            rule.setAlpha((int) (190 * a));
            canvas.drawLine(x, ruleY, x + wName * 0.55f, ruleY, rule);

            subPaint.setAlpha((int) (225 * a));
            canvas.drawText(sub, x, ruleY + h * 0.058f, subPaint);
        }
    }

    private String wifiLabel() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getContext()
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            if (ni == null || !ni.isConnected()) return "Not connected";
            if (ni.getType() != ConnectivityManager.TYPE_WIFI) return "Connected by cable";

            WifiManager wm = (WifiManager) getContext().getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm == null ? null : wm.getConnectionInfo();
            if (info == null) return "Connected";
            String ssid = info.getSSID();
            if (ssid != null) {
                ssid = ssid.replace("\"", "").trim();
                if (!ssid.isEmpty() && !ssid.contains("<unknown>")) return "Connected to " + ssid;
            }
            return "Connected";
        } catch (Exception e) {
            return "Connected";
        }
    }

    private void drawClock(Canvas canvas, long nowMs, int w, int h, float ease) {
        Date d = new Date(nowMs);
        clockPaint.setAlpha((int) (240 * ease));
        canvas.drawText(clockFmt.format(d), w * 0.945f, h * 0.120f, clockPaint);
        datePaint.setAlpha((int) (170 * ease));
        canvas.drawText(dateFmt.format(d).toUpperCase(Locale.ENGLISH), w * 0.945f, h * 0.163f, datePaint);
    }

    private void drawRail(Canvas canvas, int w, int h, float ease) {
        float x = w * 0.945f;
        float gap = h * 0.115f;
        float yCent = h * 0.545f;
        float r = h * 0.028f;
        float a = 0.35f + 0.65f * navAlpha;

        for (int i = 0; i < 3; i++) {
            float cy = yCent + (i - 1) * gap;
            boolean on = navFocused && navIndex == i;
            float rr = on ? r * 1.10f : r;

            navDisc.setColor(on ? 0xF2FFD9A0 : 0x4D000000);
            navDisc.setAlpha((int) (255 * a));
            canvas.drawCircle(x, cy, rr * 1.42f, navDisc);

            iconPaint.setColor(on ? 0xFF1A1206 : 0xF2FFFFFF);
            iconPaint.setAlpha((int) (255 * a));
            iconPaint.setStrokeWidth(Math.max(2f, rr * 0.17f));

            if (i == 0) drawWifiIcon(canvas, x, cy, rr);
            else if (i == 1) drawGearIcon(canvas, x, cy, rr);
            else drawInputsIcon(canvas, x, cy, rr);
        }
    }

    private void drawInputsIcon(Canvas canvas, float cx, float cy, float r) {
        RectF body = new RectF(cx - r * 0.98f, cy - r * 0.72f, cx + r * 0.98f, cy + r * 0.60f);
        canvas.drawRoundRect(body, r * 0.20f, r * 0.20f, iconPaint);
        canvas.drawLine(cx - r * 0.58f, cy + r * 0.82f, cx + r * 0.58f, cy + r * 0.82f, iconPaint);
        canvas.drawLine(cx, cy + r * 0.60f, cx, cy + r * 0.82f, iconPaint);

        Paint arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
        arrow.setStyle(Paint.Style.STROKE);
        arrow.setStrokeCap(Paint.Cap.ROUND);
        arrow.setStrokeWidth(iconPaint.getStrokeWidth());
        arrow.setColor(iconPaint.getColor());
        arrow.setAlpha(iconPaint.getAlpha());
        canvas.drawLine(cx - r * 0.62f, cy - r * 0.10f, cx + r * 0.14f, cy - r * 0.10f, arrow);
        canvas.drawLine(cx - r * 0.20f, cy - r * 0.42f, cx + r * 0.14f, cy - r * 0.10f, arrow);
        canvas.drawLine(cx - r * 0.20f, cy + r * 0.22f, cx + r * 0.14f, cy - r * 0.10f, arrow);
    }

    private void drawWifiIcon(Canvas canvas, float cx, float cy, float r) {
        iconPaint.setStyle(Paint.Style.STROKE);
        float dotY = cy + r * 0.52f;
        for (int k = 0; k < 3; k++) {
            float rr = r * (0.42f + k * 0.30f);
            RectF ring = new RectF(cx - rr, dotY - rr, cx + rr, dotY + rr);
            canvas.drawArc(ring, -138f, 96f, false, iconPaint);
        }
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(iconPaint.getColor());
        dot.setAlpha(iconPaint.getAlpha());
        canvas.drawCircle(cx, dotY, Math.max(1.8f, r * 0.15f), dot);
    }

    private void drawGearIcon(Canvas canvas, float cx, float cy, float r) {
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeWidth(Math.max(2f, r * 0.20f));
        // eight stubby teeth around a thick ring: a cog, not a sun
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4.0 + Math.PI / 8.0;
            float x1 = cx + (float) Math.cos(a) * r * 0.58f;
            float y1 = cy + (float) Math.sin(a) * r * 0.58f;
            float x2 = cx + (float) Math.cos(a) * r * 0.90f;
            float y2 = cy + (float) Math.sin(a) * r * 0.90f;
            canvas.drawLine(x1, y1, x2, y2, iconPaint);
        }
        canvas.drawCircle(cx, cy, r * 0.50f, iconPaint);
        Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG);
        hole.setStyle(Paint.Style.STROKE);
        hole.setColor(iconPaint.getColor());
        hole.setStrokeWidth(Math.max(1.6f, r * 0.14f));
        canvas.drawCircle(cx, cy, r * 0.20f, hole);
    }

    public boolean onFrame(float dt) {
        // Fixed sub-steps keep the spring identical at 30 fps and 60 fps; a single big Euler step
        // is what made the wheel lurch. Critically damped, so it arrives without bouncing.
        float stiffness = 72f;
        float damping = 2f * (float) Math.sqrt(stiffness);
        int steps = Math.max(1, Math.min(8, (int) Math.ceil(dt / 0.008f)));
        float hstep = dt / steps;
        for (int i = 0; i < steps; i++) {
            float acc = (targetPosition - position) * stiffness - positionVel * damping;
            positionVel += acc * hstep;
            position += positionVel * hstep;
        }
        boolean wheelSettled = false;
        if (Math.abs(targetPosition - position) < 0.0006f && Math.abs(positionVel) < 0.006f) {
            position = targetPosition;
            positionVel = 0f;
            wheelSettled = true;
        }

        float navTarget = navFocused ? 1f : 0f;
        navAlpha += (navTarget - navAlpha) * Math.min(1f, dt * 7.5f);
        if (Math.abs(navTarget - navAlpha) < 0.002f) navAlpha = navTarget;


        boolean settling = !wheelSettled;
        if (entrance < 1f) {
            entrance = Math.min(1f, entrance + dt / 0.60f);
            settling = true;
        }
        if (nameAlpha < 1f) {
            nameAlpha = Math.min(1f, nameAlpha + dt * 3.2f);
            settling = true;
        }
        if (launchT > 0) {
            launchT = Math.min(1f, launchT + dt * 5.5f);
            settling = true;
        }
        boolean navSettling = Math.abs(navAlpha - (navFocused ? 1f : 0f)) > 0.002f;

        for (int i = 0; i < tileScale.length; i++) {
            float target = i == selected ? 1f : 0.9f;
            if (Math.abs(target - tileScale[i]) > 0.001f) {
                tileScale[i] += (target - tileScale[i]) * Math.min(1f, dt * 12f);
                settling = true;
            }
        }

        // The lamp is aimed at the focused tile and at nothing else. The target is derived from
        // the tile's position on the wheel, which is a pure function of the spring, so the beam is
        // monotone: it makes one swing from the app you left onto the app you chose and it rests
        // there. There is deliberately no aim point chased by a second angle integrator here; that
        // pair of chained lags is what let the light wander with no input.
        float appAngle = focusedAngle();
        float tx = arcCx + (float) Math.cos(appAngle) * arcR;
        float ty = arcCy + (float) Math.sin(appAngle) * arcR;
        float beamTarget = (float) Math.atan2(ty - lanternY, tx - lanternX);
        if (beamSnap) {
            beamAngle = beamTarget;
            beamSnap = false;
        }
        float turn = (float) Math.atan2(Math.sin(beamTarget - beamAngle),
                Math.cos(beamTarget - beamAngle));
        boolean beamMoving;
        if (Math.abs(turn) < 0.0006f) {
            beamAngle = beamTarget;
            beamMoving = false;
        } else {
            beamAngle += turn * Math.min(1f, dt * 24f);
            beamMoving = true;
        }

        int minute = (int) (System.currentTimeMillis() / 60000L);
        boolean clockTick = minute != lastClockMinute;
        if (clockTick) lastClockMinute = minute;

        return settling || navSettling || clockTick || beamMoving;
    }

    public boolean handleKey(int keyCode) {
        if (navFocused) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    navIndex = Math.floorMod(navIndex - 1, 3);
                    sound.step(0);
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    navIndex = Math.floorMod(navIndex + 1, 3);
                    sound.step(0);
                    invalidate();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    activateNav();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_BACK:
                case KeyEvent.KEYCODE_MENU:
                    navFocused = false;
                    invalidate();
                    return true;
                default:
                    return true;
            }
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                step(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                step(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MENU:
                navFocused = true;
                navIndex = 1;
                sound.step(0);
                invalidate();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                launch();
                return true;
            case KeyEvent.KEYCODE_MUTE:
                sound.toggle();
                return true;
            case KeyEvent.KEYCODE_SETTINGS:
            case KeyEvent.KEYCODE_INFO:
                openSettings();
                return true;
            case KeyEvent.KEYCODE_BACK:
                return true;
            default:
                return false;
        }
    }

    private void activateNav() {
        if (navIndex == 0) openWifi();
        else if (navIndex == 1) openSettings();
        else openInputs();
    }

    private void openInputs() {
        try {
            Intent i = new Intent("com.android.tv.action.VIEW_INPUTS");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        } catch (Exception e) {
            openSettings();
        }
    }

    private void openWifi() {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_WIFI_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        } catch (Exception e) {
            openSettings();
        }
    }

    private void openSettings() {
        try {
            Intent s = new Intent(android.provider.Settings.ACTION_SETTINGS);
            s.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(s);
        } catch (Exception ignored) {
        }
    }

    private void step(int delta) {
        if (apps.isEmpty()) return;
        int n = apps.size();
        // Count in unwrapped wheel space only. The old code wrapped this index but left the
        // continuous position unwrapped, so `selected - position` drifted away by n and the beam
        // was aimed at a point that orbited the whole screen.
        targetPosition += delta;
        int wrapped = Math.round(targetPosition) % n;
        if (wrapped < 0) wrapped += n;
        selected = wrapped;
        nameAlpha = 0f;
        sound.step(0);
        invalidate();
    }

    private void launch() {
        if (apps.isEmpty()) return;
        final AppEntry e = apps.get(selected);
        final Intent i = repo.intentFor(e.pkg);
        if (i == null) return;
        repo.recordLaunch(e.pkg);
        launchT = 0.001f;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    getContext().startActivity(i);
                } catch (Exception ignored) {
                }
                launchT = 0f;
            }
        }, 130);
    }
}
