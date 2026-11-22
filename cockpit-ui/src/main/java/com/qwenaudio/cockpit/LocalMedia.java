package com.qwenaudio.cockpit;

import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.session.MediaController;
import androidx.media3.session.SessionToken;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionResult;
import com.google.common.util.concurrent.ListenableFuture;
import org.json.JSONObject;
import java.util.concurrent.atomic.AtomicBoolean;

final class LocalMedia {
    interface Reply { void done(JSONObject data, String error); }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ListenableFuture<MediaController> connection;
    private boolean closed;

    LocalMedia(Context context) { this.context = context.getApplicationContext(); }

    void call(String action, Reply reply) {
        main.post(() -> {
            if (closed) { reply.done(null, "媒体连接已关闭"); return; }
            try {
                if (connection == null) {
                    SessionToken token = new SessionToken(context, new ComponentName(
                        "com.txzing.mediax", "com.txzing.mediax.core.service.MediaPlaybackService"));
                    connection = new MediaController.Builder(context, token)
                        .setListener(new MediaController.Listener() {
                            @Override public void onDisconnected(MediaController controller) {
                                if (connection != null && connection.isDone()) {
                                    try { if (connection.get() == controller) connection = null; }
                                    catch (Exception ignored) {}
                                }
                            }
                        }).buildAsync();
                }
                final ListenableFuture<MediaController> pending = connection;
                AtomicBoolean finished = new AtomicBoolean();
                Runnable timeout = () -> {
                    if (!finished.compareAndSet(false, true)) return;
                    if (connection == pending && !pending.isDone()) {
                        connection = null;
                        MediaController.releaseFuture(pending);
                    }
                    reply.done(null, "连接车机媒体服务超时");
                };
                main.postDelayed(timeout, 3500);
                pending.addListener(() -> {
                    if (!finished.compareAndSet(false, true)) return;
                    main.removeCallbacks(timeout);
                    if (closed || connection != pending) { reply.done(null, "媒体服务已断开"); return; }
                    try {
                        MediaController player = pending.get();
                        if (!player.isConnected()) throw new IllegalStateException("媒体服务已断开");
                        if (action.equals("status")) { reply.done(snapshot(player), null); return; }
                        String operation;
                        switch (action) {
                            case "play": operation = "PLAY"; break;
                            case "pause": operation = "PAUSE"; break;
                            case "next": operation = "SEEK_TO_NEXT"; break;
                            case "previous": operation = "SEEK_TO_PREVIOUS"; break;
                            default: reply.done(null, "不支持的媒体操作"); return;
                        }
                        if (!hasMediaXActions(player)) {
                            reply.done(null, "车机未向本应用开放 MediaX 控制命令"); return;
                        }
                        Bundle args = new Bundle();
                        args.putString("action_id", operation);
                        ListenableFuture<SessionResult> result = player.sendCustomCommand(
                            new SessionCommand("play_action", args), args);
                        AtomicBoolean responded = new AtomicBoolean();
                        Runnable commandTimeout = () -> {
                            if (responded.compareAndSet(false, true))
                                reply.done(null, "车机媒体未确认指令，请检查实际播放状态");
                        };
                        main.postDelayed(commandTimeout, 3500);
                        result.addListener(() -> {
                            if (!responded.compareAndSet(false, true)) return;
                            main.removeCallbacks(commandTimeout);
                            try {
                                int code = result.get().resultCode;
                                if (code != SessionResult.RESULT_SUCCESS) {
                                    reply.done(null, "车机媒体拒绝操作，结果码 " + code); return;
                                }
                                reply.done(snapshot(player).put("requested", action).put("accepted", true), null);
                            } catch (Exception error) {
                                reply.done(null, "读取车机媒体执行结果失败");
                            }
                        }, main::post);
                    } catch (Exception error) {
                        if (connection == pending) {
                            connection = null;
                            MediaController.releaseFuture(pending);
                        }
                        Throwable cause = error.getCause() == null ? error : error.getCause();
                        reply.done(null, "车机媒体服务调用失败：" + cause.getClass().getSimpleName());
                    }
                }, main::post);
            } catch (Exception error) {
                reply.done(null, "无法连接车机媒体服务：" + error.getClass().getSimpleName());
            }
        });
    }

    private JSONObject snapshot(MediaController player) throws Exception {
        MediaMetadata metadata = player.isCommandAvailable(Player.COMMAND_GET_METADATA)
            ? player.getMediaMetadata() : MediaMetadata.EMPTY;
        boolean hasCurrent = player.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM);
        long duration = hasCurrent ? player.getDuration() : 0;
        byte[] art = metadata.artworkData;
        return new JSONObject().put("connected", true)
            .put("album", metadata.albumTitle == null ? "" : metadata.albumTitle.toString())
            .put("duration", duration > 0 ? duration / 1000.0 : 0)
            .put("position", hasCurrent ? Math.max(0, player.getCurrentPosition()) / 1000.0 : 0)
            .put("artwork", art != null && art.length <= 96_000 && art.length >= 8 &&
                ((art[0] == (byte)0xff && art[1] == (byte)0xd8) ||
                 (art[0] == (byte)0x89 && art[1] == 'P' && art[2] == 'N' && art[3] == 'G'))
                ? "data:image/" + (art[0] == (byte)0xff ? "jpeg" : "png") + ";base64,"
                    + android.util.Base64.encodeToString(art, android.util.Base64.NO_WRAP) : "")
            .put("title", metadata.title == null ? "" : metadata.title.toString())
            .put("artist", metadata.artist == null ? "" : metadata.artist.toString())
            .put("playing", player.isPlaying())
            .put("playbackState", player.getPlaybackState())
            .put("canPlayPause", hasMediaXActions(player))
            .put("canNext", hasMediaXActions(player))
            .put("canPrevious", hasMediaXActions(player));
    }

    private boolean hasMediaXActions(MediaController player) {
        return player.getAvailableSessionCommands().contains(new SessionCommand("play_action", Bundle.EMPTY));
    }

    void close() {
        main.post(() -> {
            closed = true;
            ListenableFuture<MediaController> pending = connection;
            connection = null;
            if (pending != null) MediaController.releaseFuture(pending);
        });
    }
}
