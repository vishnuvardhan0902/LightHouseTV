package tv.lighthouse.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

public class MainActivity extends Activity implements Choreographer.FrameCallback {

    private LightHouseView view;
    private SoundEngine sound;
    private AppRepository repo;
    private long lastNs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);

        repo = new AppRepository(this);
        sound = new SoundEngine(this);
        view = new LightHouseView(this, repo, sound);
        setContentView(view);
        view.requestFocus();

        // Android 9 and later hide the SSID from apps without a location grant, so the Wi-Fi line
        // in the rail reads "Connected" instead of the network name. Ask for it here; the install
        // script grants it over adb so the dialog normally never appears.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (view != null) view.invalidate();
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.reloadApps();
        sound.startDrone();
        lastNs = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onPause() {
        Choreographer.getInstance().removeFrameCallback(this);
        sound.stopDrone();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        sound.stopDrone();
        super.onDestroy();
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        float dt = lastNs == 0 ? 0.016f : (frameTimeNanos - lastNs) / 1_000_000_000f;
        lastNs = frameTimeNanos;
        if (dt > 0.1f) dt = 0.1f;
        if (view.onFrame(dt)) view.invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (view != null && view.handleKey(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }
}
