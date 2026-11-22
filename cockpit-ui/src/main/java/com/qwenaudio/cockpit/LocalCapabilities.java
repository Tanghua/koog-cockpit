package com.qwenaudio.cockpit;
import android.app.Activity;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Reuses the verified local service adapters without the old cloud gateway. */
public final class LocalCapabilities {
    public interface Reply { void complete(JSONObject value, String error); }
    private final LocalMedia media;
    private final LocalCockpitApps apps;
    public LocalCapabilities(Activity activity, Consumer<JSONObject> navigation) {
        media = new LocalMedia(activity);
        apps = new LocalCockpitApps(activity, navigation);
    }
    public void call(String action, JSONObject arguments, Reply reply) {
        if (action.startsWith("media_")) media.call(action.substring(6), reply::complete);
        else apps.call(action, arguments, reply::complete);
    }
    public void close() { apps.close(); media.close(); }
}
