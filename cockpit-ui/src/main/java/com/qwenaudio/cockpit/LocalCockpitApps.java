package com.qwenaudio.cockpit;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import java.util.function.Consumer;
import android.content.Intent;
import org.json.JSONObject;

final class LocalCockpitApps {
    static final String MAP = "com.autonavi.amapauto";
    static final String HVAC = "com.txznet.airconditioner";
    private static final String AMAP_RECEIVE = "AUTONAVI_STANDARD_BROADCAST_RECV";
    // AmapAuto V5.1 sections 1.3.2 and 5.2.1.
    private static final int AMAP_OPEN_MAIN_MAP = 10034;
    private static final int AMAP_KEYWORD_SEARCH = 10036;

    private static final String AMAP_SEND = "AUTONAVI_STANDARD_BROADCAST_SEND";
    private final android.app.Activity activity;
    private final Consumer<JSONObject> navEvent;
    private final Handler main = new Handler(Looper.getMainLooper());
    private String keyword;
    private JSONArray candidates = new JSONArray();
    private int selected = -1;
    private boolean routeRequested;
    private final Runnable routeTimeout = this::expireRoute;

    private void expireRoute() {
        if (!routeRequested) return;
        routeRequested = false;
        try {
            navEvent.accept(new JSONObject().put("type", "routes").put("index", selected)
                .put("message", "未收到高德路线结果，可重试或在高德查看"));
        } catch (org.json.JSONException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    private final Runnable searchTimeout = () -> finishSearch(2, new JSONArray(), "高德搜索超时，请重试");
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!AMAP_SEND.equals(intent.getAction())) return;
            try {
                switch (intent.getIntExtra("KEY_TYPE", -1)) {
                    case 10042:
                        if (keyword == null) return;
                        String raw = intent.getStringExtra("EXTRA_RESULT");
                        int code = intent.getIntExtra("SEARCH_RESULT_CODE", raw == null || raw.isEmpty() ? 1 : 0);
                        JSONArray results = raw == null || raw.isEmpty() ? new JSONArray() : new JSONArray(raw);
                        finishSearch(code, results, code == 3 ? "高德当前无网络且缺少离线地图" : "");
                        break;
                    case 10019:
                        int state = intent.getIntExtra("EXTRA_STATE", -1);
                        if (state == 8 || state == 9 || state == 10)
                            navEvent.accept(new JSONObject().put("type", "status").put("state", state));
                        break;
                    case 10001:
                        if (intent.getIntExtra("TYPE", -1) != 0) return;
                        android.os.Bundle fields = intent.getExtras();
                        navEvent.accept(new JSONObject().put("type", "guidance")
                            .put("remainingMeters", Integer.parseInt(String.valueOf(fields.get("ROUTE_REMAIN_DIS"))))
                            .put("remainingSeconds", Integer.parseInt(String.valueOf(fields.get("ROUTE_REMAIN_TIME"))))
                            .put("nextTurnMeters", intent.getIntExtra("SEG_REMAIN_DIS", -1))
                            .put("nextRoad", intent.getStringExtra("NEXT_ROAD_NAME"))
                            .put("turnIcon", intent.getIntExtra("NEW_ICON", -1)));
                        break;
                    case 10056:
                        if (!routeRequested || selected < 0) return;
                        JSONObject route = new JSONObject(intent.getStringExtra("EXTRA_ROAD_INFO"));
                        if (!candidates.getJSONObject(selected).optString("name").equals(route.optString("ToPoiName"))) return;
                        routeRequested = false;
                        main.removeCallbacks(routeTimeout);
                        navEvent.accept(new JSONObject().put("type", "routes").put("index", selected)
                            .put("paths", route.optJSONArray("path_info")));
                        break;
                    default: break;
                }
            } catch (Exception malformed) {
                if (intent.getIntExtra("KEY_TYPE", -1) == 10042 && keyword != null)
                    finishSearch(2, new JSONArray(), "高德返回了无法读取的搜索结果");
            }
        }
    };

    LocalCockpitApps(android.app.Activity activity, Consumer<JSONObject> navEvent) {
        this.activity = activity;
        this.navEvent = navEvent;
        IntentFilter filter = new IntentFilter(AMAP_SEND);
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        else activity.registerReceiver(receiver, filter);
    }

    void close() {
        main.removeCallbacks(searchTimeout);
        main.removeCallbacks(routeTimeout);
        activity.unregisterReceiver(receiver);
    }

    private void finishSearch(int code, JSONArray results, String message) {
        if (keyword == null) return;
        main.removeCallbacks(searchTimeout);
        candidates = code == 0 || code == 5 ? results : new JSONArray();
        try {
            navEvent.accept(new JSONObject().put("type", "results").put("keyword", keyword)
                .put("code", code).put("candidates", candidates).put("message", message));
        } catch (org.json.JSONException impossible) {
            throw new IllegalStateException(impossible);
        } finally { keyword = null; }
    }

    private void searchInside(JSONObject input, LocalMedia.Reply reply) throws org.json.JSONException {
        String destination = input.optString("destination", "").trim();
        String city = input.optString("city", "").trim();
        if (destination.isEmpty() || destination.length() > 100 || city.length() > 60) {
            reply.done(null, "请输入有效目的地（最多 100 字）"); return;
        }
        if (keyword != null) { reply.done(null, "正在搜索，请等待高德返回"); return; }
        Intent request = amapRequest(10023).putExtra("EXTRA_SEARCHTYPE", 0)
            .putExtra("EXTRA_KEYWORD", destination).putExtra("EXTRA_DEV", 0).putExtra("EXTRA_MAXCOUNT", 12);
        if (!city.isEmpty()) request.putExtra("EXTRA_CITY", city);
        if (!sendAmapRequest(request, reply)) return;
        keyword = destination;
        candidates = new JSONArray();
        selected = -1;
        routeRequested = false;
        main.postDelayed(searchTimeout, 12000);
        navEvent.accept(new JSONObject().put("type", "searching").put("keyword", destination));
        reply.done(new JSONObject().put("status", "search_requested"), null);
    }

    private void navigateToCandidate(JSONObject input, boolean plan, LocalMedia.Reply reply) throws org.json.JSONException {
        int index = input.optInt("index", -1);
        JSONObject place = candidates.optJSONObject(index);
        if (place == null || place.optString("name").isEmpty()) {
            reply.done(null, "请先从当前搜索结果选择目的地"); return;
        }
        double lat = place.optDouble("latitude"), lon = place.optDouble("longitude");
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180
            || (lat == 0 && lon == 0)) {
            reply.done(null, "该地点缺少有效导航坐标"); return;
        }
        Intent request = amapRequest(plan ? 10007 : 10038)
            .putExtra("EXTRA_D_POI_ID", place.optString("poiid"))
            .putExtra("EXTRA_POITYPE", place.optString("extra_poitype"));
        if (plan) request.putExtra("EXTRA_DNAME", place.optString("name"))
            .putExtra("EXTRA_DLAT", lat).putExtra("EXTRA_DLON", lon).putExtra("EXTRA_DEV", 0).putExtra("EXTRA_M", -1);
        else request.putExtra("POINAME", place.optString("name")).putExtra("LAT", lat).putExtra("LON", lon)
            .putExtra("DEV", 0).putExtra("STYLE", -1);
        if (!sendAmapRequest(request, reply)) return;
        selected = index;
        routeRequested = plan;
        main.removeCallbacks(routeTimeout);
        if (plan) {
            navEvent.accept(new JSONObject().put("type", "routes").put("index", index).put("pending", true));
            main.postDelayed(routeTimeout, 25000);
        }
        reply.done(new JSONObject().put("status", plan ? "route_requested" : "navigation_requested"), null);
    }

    private Intent amapRequest(int type) {
        return new Intent(AMAP_RECEIVE)
            .setPackage(MAP)
            .putExtra("KEY_TYPE", type)
            .putExtra("SOURCE_APP", "Qwen Cockpit");
    }

    private boolean sendAmapRequest(Intent request, LocalMedia.Reply reply) {
        if (activity.getPackageManager().queryBroadcastReceivers(request, 0).isEmpty()) {
            reply.done(null, "未检测到高德车机版的标准广播接口");
            return false;
        }
        activity.sendBroadcast(request);
        return true;
    }

    void call(String operation, JSONObject input, LocalMedia.Reply reply) {
        activity.runOnUiThread(() -> {
            try {
                if (!activity.hasWindowFocus()) {
                    reply.done(null, "请先将 Qwen 座舱切到前台"); return;
                }
                switch (operation) {
                    case "aircon":
                        if (activity.startService(new Intent().setComponent(new ComponentName(
                            HVAC, HVAC + ".AirConditionerService"))) == null) {
                            reply.done(null, "未找到车机空调面板服务"); return;
                        }
                        reply.done(new JSONObject().put("status", "page_open_requested")
                            .put("message", "已请求打开系统空调页面，未改变空调设定"), null);
                        return;
                    case "map":
                        if (!sendAmapRequest(amapRequest(AMAP_OPEN_MAIN_MAP), reply)) return;
                        reply.done(new JSONObject().put("status", "map_open_requested"), null);
                        return;
                    case "navigationCandidates":
                        searchInside(input, reply);
                        return;
                    case "navigationPlan":
                    case "navigationStart":
                        navigateToCandidate(input, operation.equals("navigationPlan"), reply);
                        return;
                    case "navigation":
                        String destination = input.optString("destination", "").trim();
                        if (destination.isEmpty() || destination.length() > 100) {
                            reply.done(null, "请输入有效目的地（最多 100 字）"); return;
                        }
                        Intent search = amapRequest(AMAP_KEYWORD_SEARCH).putExtra("KEYWORDS", destination);
                        if (!sendAmapRequest(search, reply)) return;
                        reply.done(new JSONObject().put("status", "search_requested")
                            .put("destination", destination)
                            .put("message", "已向车机地图请求搜索，请在地图中选择目的地；尚未开始导航"), null);
                        return;
                    default: reply.done(null, "不支持的车机操作");
                }
            } catch (Exception error) {
                reply.done(null, "无法调用车机应用：" + error.getClass().getSimpleName());
            }
        });
    }
}
