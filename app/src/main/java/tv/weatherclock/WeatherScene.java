package tv.weatherclock;

import android.animation.ArgbEvaluator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The screensaver's scene (layout dream_weather): the clock, date and weather on black over a slow ambient
 * glow, after the cleanyourtv launcher's ClockScene. Everything stays centred; every minute it is nudged a
 * little and the glow shifts colour, so nothing burns in. The forecast comes from the cache at once and is refreshed every
 * 30 minutes while the screensaver runs; a forecast older than 6 hours is hidden rather than shown as current.
 */
final class WeatherScene {

    private static final long MOVE_MS = 60_000L;
    /** How far from the centre the content may drift. */
    private static final int DRIFT_X_DP = 48;
    private static final int DRIFT_Y_DP = 32;
    private static final long REFRESH_MS = 30 * 60_000L;
    private static final long STALE_MS = 20 * 60_000L;
    private static final long TOO_OLD_MS = 6 * 60 * 60_000L;
    private static final int GLOW_STEPS = 16;
    private static final long GLOW_STEP_MS = 500L;
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final View content;
    private final View weather;
    private final TextView nowIcon, nowTemp, nowText, nowDetail, place;
    private final LinearLayout days;
    private final GradientDrawable glow;
    private int colorIndex;
    private int glowColor;
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            move();
            shiftGlow();
            handler.postDelayed(this, MOVE_MS);
        }
    };

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            fetch();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    /** root is the inflated dream_weather layout. */
    WeatherScene(Context context, View root) {
        this.context = context;
        content = root.findViewById(R.id.content);
        weather = root.findViewById(R.id.weather);
        nowIcon = root.findViewById(R.id.nowIcon);
        nowTemp = root.findViewById(R.id.nowTemp);
        nowText = root.findViewById(R.id.nowText);
        nowDetail = root.findViewById(R.id.nowDetail);
        place = root.findViewById(R.id.place);
        days = root.findViewById(R.id.days);
        View ambient = root.findViewById(R.id.ambient);
        glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambient.setBackground(glow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                glow.setGradientRadius((r - l) * 0.55f));
        colorIndex = random.nextInt(PALETTE.length);
        setGlow(PALETTE[colorIndex]);
    }

    void start() {
        running = true;
        Weather.Forecast cached = Weather.cached(context);
        show(cached);
        content.setAlpha(0f);
        content.post(() -> {
            place();
            content.animate().alpha(1f).setDuration(1200).start();
        });
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(tick, MOVE_MS);
        boolean stale = cached == null || System.currentTimeMillis() - cached.fetchedAt > STALE_MS;
        handler.postDelayed(refresh, stale ? 0 : REFRESH_MS);
    }

    void stop() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        content.animate().cancel();
    }

    void destroy() {
        stop();
        io.shutdownNow();
    }

    private void fetch() {
        io.execute(() -> {
            Weather.Forecast f;
            try {
                f = Weather.fetch(context);
            } catch (Exception e) {
                return;   // keep showing the cached forecast until it is too old
            }
            Weather.Forecast result = f;
            handler.post(() -> {
                if (running && result != null) show(result);
            });
        });
    }

    private void show(Weather.Forecast f) {
        if (f == null || System.currentTimeMillis() - f.fetchedAt > TOO_OLD_MS) {
            weather.setVisibility(View.GONE);
            return;
        }
        nowIcon.setText(Weather.icon(f.code, f.day));
        nowTemp.setText(f.temp + "°");
        nowText.setText(Weather.describe(f.code));
        nowDetail.setText("H " + f.max + "°   L " + f.min + "°   ·   Feels " + f.feels + "°");
        place.setText(f.place);
        days.removeAllViews();
        for (int i = 0; i < f.days.size(); i++) {
            Weather.Day d = f.days.get(i);
            LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(92), ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.setMarginStart(dp(14));
            col.addView(label(d.name.toUpperCase(), 13, 0x8CFFFFFF, R.font.outfit_medium, 0.14f));
            TextView icon = label(Weather.icon(d.code, true), 26, 0xFFFFFFFF, 0, 0f);
            icon.setPadding(0, dp(4), 0, dp(4));
            col.addView(icon);
            col.addView(label(d.max + "°  " + d.min + "°", 16, 0xB3FFFFFF, R.font.outfit_regular, 0.02f));
            days.addView(col, lp);
        }
        weather.setVisibility(View.VISIBLE);
        // The forecast changes the content's size: centre it again once it is laid out.
        content.post(this::place);
    }

    private TextView label(String text, int sp, int color, int font, float spacing) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLetterSpacing(spacing);
        t.setGravity(Gravity.CENTER_HORIZONTAL);   // the views fill their column, so centre the text in it
        if (font != 0) t.setTypeface(context.getResources().getFont(font));
        return t;
    }

    private int dp(int v) {
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }

    private void move() {
        content.animate().alpha(0f).setDuration(900).withLayer().withEndAction(() -> {
            place();
            content.animate().alpha(1f).setDuration(1200).withLayer().start();
        }).start();
    }

    /** Centred, nudged a little at random each minute so nothing burns in; the glow sits behind it. */
    private void place() {
        View parent = (View) content.getParent();
        float x = (parent.getWidth() - content.getWidth()) / 2f + (random.nextFloat() * 2 - 1) * dp(DRIFT_X_DP);
        float y = (parent.getHeight() - content.getHeight()) / 2f + (random.nextFloat() * 2 - 1) * dp(DRIFT_Y_DP);
        x = Math.max(0, Math.min(x, parent.getWidth() - content.getWidth()));
        y = Math.max(0, Math.min(y, parent.getHeight() - content.getHeight()));
        content.setX(x);
        content.setY(y);
        centreGlow(x, y);
    }

    private void centreGlow(float x, float y) {
        View parent = (View) content.getParent();
        if (parent.getWidth() > 0 && parent.getHeight() > 0) {
            glow.setGradientCenter((x + content.getWidth() / 2f) / parent.getWidth(),
                    (y + content.getHeight() / 2f) / parent.getHeight());
        }
    }

    private void shiftGlow() {
        int from = glowColor;
        colorIndex = (colorIndex + 1) % PALETTE.length;
        int to = PALETTE[colorIndex];
        ArgbEvaluator mix = new ArgbEvaluator();
        for (int i = 1; i <= GLOW_STEPS; i++) {
            float f = (float) i / GLOW_STEPS;
            handler.postDelayed(() -> setGlow((int) mix.evaluate(f, from, to)), i * GLOW_STEP_MS);
        }
    }

    private void setGlow(int color) {
        glowColor = color;
        int c = color & 0x00FFFFFF;
        glow.setColors(new int[]{0x40000000 | c, 0x14000000 | c, c});
    }
}
