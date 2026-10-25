package tv.weatherclock;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The forecast for one place from Open-Meteo (no key, no account): only the place's coordinates are sent.
 * The place is either picked by hand or, by default, found from the TV's public IP address (ipwho.is, with
 * geojs.io as a fallback), which those services see on any connection anyway.
 * The last answer is kept in preferences, so the screensaver shows something at once and survives a dropped
 * connection.
 */
final class Weather {

    private static final String PREFS = "weather";
    private static final String FORECAST_URL = "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s"
            + "&current=temperature_2m,apparent_temperature,weather_code,is_day"
            + "&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=4";
    private static final String SEARCH_URL =
            "https://geocoding-api.open-meteo.com/v1/search?count=5&format=json&language=en&name=";
    /** Automatic location: the city of the TV's public IP address, looked up again once a day. */
    private static final String[] IP_LOOKUP = {"https://ipwho.is/", "https://get.geojs.io/v1/ip/geo.json"};
    private static final long RELOCATE_MS = 24 * 60 * 60_000L;

    static final class Day {
        String name;
        int code;
        int max;
        int min;
    }

    static final class Forecast {
        long fetchedAt;
        String place;
        int temp;
        int feels;
        int max;
        int min;
        int code;
        boolean day;
        final List<Day> days = new ArrayList<>();
    }

    static final class Place {
        final String name;
        final String label;
        final double lat;
        final double lon;

        Place(String name, String label, double lat, double lon) {
            this.name = name;
            this.label = label;
            this.lat = lat;
            this.lon = lon;
        }
    }

    private Weather() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String placeName(Context c) {
        return prefs(c).getString("place", null);
    }

    /** Automatic unless a city was picked by hand. */
    static boolean isAuto(Context c) {
        return prefs(c).getBoolean("auto", true);
    }

    /** A city picked by hand: switches automatic location off. */
    static void setPlace(Context c, Place p) {
        store(c, p).putBoolean("auto", false).apply();
    }

    /** Back to automatic: forget the place so the next fetch looks it up. */
    static void setAuto(Context c) {
        prefs(c).edit().putBoolean("auto", true)
                .remove("place").remove("lat").remove("lon").remove("locatedAt")
                .remove("json").remove("fetchedAt")
                .apply();
    }

    private static SharedPreferences.Editor store(Context c, Place p) {
        return prefs(c).edit()
                .putString("place", p.name)
                .putString("lat", Double.toString(p.lat))
                .putString("lon", Double.toString(p.lon))
                .remove("json").remove("fetchedAt");
    }

    /** Blocking: in automatic mode, look the place up from the IP address when it is missing or a day old. */
    static void locateIfNeeded(Context c) throws IOException, JSONException {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("auto", true)) return;
        long now = System.currentTimeMillis();
        if (p.getString("lat", null) != null && now - p.getLong("locatedAt", 0) < RELOCATE_MS) return;
        IOException failure = null;
        for (String url : IP_LOOKUP) {
            try {
                JSONObject r = new JSONObject(get(url));
                if (!r.optBoolean("success", true)) continue;
                String city = r.optString("city", "");
                Place place = new Place(city, city, Double.parseDouble(r.getString("latitude")),
                        Double.parseDouble(r.getString("longitude")));
                // Same city as before: keep the cached forecast rather than throwing it away.
                SharedPreferences.Editor e = city.equals(p.getString("place", null))
                        ? p.edit() : store(c, place);
                e.putString("lat", Double.toString(place.lat))
                        .putString("lon", Double.toString(place.lon))
                        .putLong("locatedAt", now).apply();
                return;
            } catch (IOException e) {
                failure = e;
            } catch (JSONException | NumberFormatException e) {
                failure = new IOException(e);
            }
        }
        if (failure != null && p.getString("lat", null) == null) throw failure;
    }

    /** The last forecast saved, or null. */
    static Forecast cached(Context c) {
        SharedPreferences p = prefs(c);
        String json = p.getString("json", null);
        if (json == null) return null;
        try {
            return parse(json, p.getLong("fetchedAt", 0), p.getString("place", ""));
        } catch (JSONException e) {
            return null;
        }
    }

    /** Blocking: call off the main thread. Null when no place is set. */
    static Forecast fetch(Context c) throws IOException, JSONException {
        locateIfNeeded(c);
        SharedPreferences p = prefs(c);
        String lat = p.getString("lat", null);
        String lon = p.getString("lon", null);
        if (lat == null || lon == null) return null;
        String json = get(String.format(Locale.US, FORECAST_URL, lat, lon));
        long now = System.currentTimeMillis();
        Forecast f = parse(json, now, p.getString("place", ""));   // only a parseable answer is kept
        p.edit().putString("json", json).putLong("fetchedAt", now).apply();
        return f;
    }

    /** Blocking: up to five places matching the name. */
    static List<Place> search(String name) throws IOException, JSONException {
        JSONObject root = new JSONObject(get(SEARCH_URL + URLEncoder.encode(name.trim(), "UTF-8")));
        List<Place> places = new ArrayList<>();
        JSONArray results = root.optJSONArray("results");
        if (results == null) return places;
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String n = r.getString("name");
            StringBuilder label = new StringBuilder(n);
            String admin = r.optString("admin1", "");
            if (!admin.isEmpty() && !admin.equals(n)) label.append(", ").append(admin);
            String country = r.optString("country", "");
            if (!country.isEmpty()) label.append(", ").append(country);
            places.add(new Place(n, label.toString(), r.getDouble("latitude"), r.getDouble("longitude")));
        }
        return places;
    }

    static Forecast parse(String json, long fetchedAt, String place) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONObject now = root.getJSONObject("current");
        JSONObject daily = root.getJSONObject("daily");
        JSONArray time = daily.getJSONArray("time");
        JSONArray codes = daily.getJSONArray("weather_code");
        JSONArray max = daily.getJSONArray("temperature_2m_max");
        JSONArray min = daily.getJSONArray("temperature_2m_min");

        Forecast f = new Forecast();
        f.fetchedAt = fetchedAt;
        f.place = place;
        f.temp = (int) Math.round(now.getDouble("temperature_2m"));
        f.feels = (int) Math.round(now.getDouble("apparent_temperature"));
        f.code = now.getInt("weather_code");
        f.day = now.optInt("is_day", 1) == 1;
        f.max = (int) Math.round(max.getDouble(0));
        f.min = (int) Math.round(min.getDouble(0));
        for (int i = 1; i < time.length(); i++) {
            Day d = new Day();
            d.name = LocalDate.parse(time.getString(i)).getDayOfWeek()
                    .getDisplayName(TextStyle.SHORT, Locale.getDefault());
            d.code = codes.getInt(i);
            d.max = (int) Math.round(max.getDouble(i));
            d.min = (int) Math.round(min.getDouble(i));
            f.days.add(d);
        }
        return f;
    }

    /** WMO weather code as a short description. */
    static String describe(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mainly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Overcast";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return code >= 65 ? "Heavy rain" : "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Thunderstorm";
        return "";
    }

    /** WMO weather code as an emoji, with a moon for clear nights. */
    static String icon(int code, boolean day) {
        if (code == 0) return day ? "☀️" : "🌙";
        if (code == 1) return day ? "🌤️" : "🌙";
        if (code == 2) return "⛅";
        if (code == 3) return "☁️";
        // Noto Color Emoji, the emoji font on Android TV and Google TV, draws 🌫️ as a full square picture, which
        // reads as a white box beside the other cut-out icons. Use the plain cloud; the text next to it says "Fog".
        if (code == 45 || code == 48) return "☁️";
        if ((code >= 51 && code <= 57) || (code >= 80 && code <= 82)) return "🌦️";
        if (code >= 61 && code <= 67) return "🌧️";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "🌨️";
        if (code >= 95) return "⛈️";
        return "☁️";
    }

    private static String get(String url) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(10_000);
        con.setReadTimeout(10_000);
        try {
            if (con.getResponseCode() != 200) throw new IOException("HTTP " + con.getResponseCode());
            try (InputStream in = con.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            con.disconnect();
        }
    }
}
