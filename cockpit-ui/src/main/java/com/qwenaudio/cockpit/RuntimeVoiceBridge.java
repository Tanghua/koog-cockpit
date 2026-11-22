package com.qwenaudio.cockpit;

import android.content.Context;
import android.media.*;
import android.media.audiofx.*;
import android.os.SystemClock;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import org.json.*;
import okhttp3.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public final class RuntimeVoiceBridge {
    private final android.app.Activity activity;
    private final java.util.function.Consumer<String> microphoneRequest;
    private final WebView view;
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final ExecutorService control = Executors.newSingleThreadExecutor();
    private final Set<String> closedStreams = ConcurrentHashMap.newKeySet();
    private volatile String currentPlaybackId = "";
    private final Map<String,Call> streams = new ConcurrentHashMap<>();
    private final Map<String,WebSocket> sockets = new ConcurrentHashMap<>();
    private final Map<String,Boolean> pendingSockets = new ConcurrentHashMap<>();
    private final Map<String,List<Cookie>> cookies = new ConcurrentHashMap<>();
    private final OkHttpClient http;
    private volatile String origin;
    private final CredentialVault vault;
    private volatile WebSocket voiceSocket;
    private volatile boolean capturing = false, requested = false, destroyed = false;
    public volatile boolean foreground = true;
    private volatile int inputRate = 16000;
    private AudioRecord recorder;
    private final java.util.concurrent.atomic.AtomicLong capturedBytes=new java.util.concurrent.atomic.AtomicLong(), playedBytes=new java.util.concurrent.atomic.AtomicLong();
    private AcousticEchoCanceler echo;
    private NoiseSuppressor noise;
    private final AudioManager manager;
    private AudioFocusRequest focus;
    private int focusGeneration;
    private boolean mediaOwnsFocus;
    private final BlockingQueue<AudioChunk> playback = new LinkedBlockingQueue<>(300);
    private final Object playbackLock = new Object();
    private AudioTrack track;
    private volatile int generation = 0;
    private final Set<String> speaking = new HashSet<>();
    private final Set<String> cancelled = new LinkedHashSet<>();

    public RuntimeVoiceBridge(android.app.Activity a, WebView v, java.util.function.Consumer<String> microphoneRequest) {
        activity=a; view=v; this.microphoneRequest=microphoneRequest; vault=new CredentialVault(a);
        String defaultOrigin=cockpit.ui.BuildConfig.VOICE_SERVER_ORIGIN;
        origin=a.getPreferences(Context.MODE_PRIVATE).getString("origin", defaultOrigin);
        File access=new File(a.getFilesDir(), "voice-access.json");
        if(access.isFile()) {
            try {
                JSONObject record=object(new String(java.nio.file.Files.readAllBytes(access.toPath()),java.nio.charset.StandardCharsets.UTF_8));
                String configured=EndpointPolicy.origin(record.getString("origin"));
                String username=record.optString("username"), password=record.optString("password");
                if(!username.isEmpty() || !password.isEmpty()) vault.save(configured,username,password);
                origin=configured;
                a.getPreferences(Context.MODE_PRIVATE).edit().putString("origin",origin).apply();
                access.delete();
            } catch(Exception ignored) { /* Invalid local configuration is retained for correction. */ }
        }
        manager=(AudioManager)a.getSystemService(Context.AUDIO_SERVICE);
        http=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(10,TimeUnit.SECONDS).readTimeout(0,TimeUnit.MILLISECONDS)
            .cookieJar(new CookieJar() {
                public void saveFromResponse(HttpUrl url,List<Cookie> value) {
                    // Keep application CSRF cookies only in process memory.
                    List<Cookie> merged=new ArrayList<>(cookies.getOrDefault(url.host(), Collections.emptyList()));
                    for(Cookie c:value) { merged.removeIf(old -> old.name().equals(c.name())); merged.add(c); }
                    cookies.put(url.host(),merged);
                }
                public List<Cookie> loadForRequest(HttpUrl url) {
                    List<Cookie> result=new ArrayList<>();
                    for(Cookie c:cookies.getOrDefault(url.host(),Collections.emptyList())) if(c.matches(url) && c.expiresAt()>System.currentTimeMillis()) result.add(c);
                    return result;
                }
            }).build();
        io.execute(this::playLoop);
    }
    private JSONObject object(String text) throws JSONException { return new JSONObject(text); }
    private JSONObject json(Object... values) {
        JSONObject o=new JSONObject();
        try { for(int i=0;i<values.length;i+=2) o.put((String)values[i],values[i+1]); } catch(JSONException ignored) {}
        return o;
    }
    private void emit(String type,JSONObject data) {
        if(destroyed) return;
        String message=json("type",type,"data",data).toString();
        activity.runOnUiThread(() -> { if(!destroyed) view.evaluateJavascript("window.__qwaNativeEvent&&window.__qwaNativeEvent("+JSONObject.quote(message)+")",null); });
    }
    private void ok(String id,JSONObject value) { emit("result",json("id",id,"value",value)); }
    public void fail(String id,String message) { if(id!=null) emit("result",json("id",id,"error",message)); }
    private String endpoint(String path) throws Exception {
        if(origin.isEmpty())throw new Exception("请先配置 Mac 地址");
        return origin+EndpointPolicy.path(path);
    }
    @JavascriptInterface public void call(String id,String method,String raw) {
        if(destroyed) return;
        control.execute(() -> {
            try {
                if(!Arrays.asList("socketOpen","socketSend","socketClose","voiceStart","voiceStop","clearPlayback","voiceStats","debugPauseCapture").contains(method))throw new Exception("未开放该语音操作");
                JSONObject p=object(raw);
                switch(method) {
                    case "config": { JSONObject login=vault.load(origin); ok(id,json("origin",origin,"native",true,"authenticated",login!=null,"username",login==null?"qwen":login.optString("username"))); break; }
                    case "configure":
                        voiceOff("endpoint_changed"); closeConnections(); cookies.clear();
                        String nextOrigin=EndpointPolicy.origin(p.getString("origin"));
                        if(!p.optString("password").isEmpty()) vault.save(nextOrigin,p.optString("username","qwen"),p.getString("password"));
                        origin=nextOrigin;
                        activity.getPreferences(Context.MODE_PRIVATE).edit().putString("origin",origin).apply();
                        ok(id,json("origin",origin)); break;
                    case "fetch": io.execute(() -> { try { fetch(id,p); } catch(Exception e) { fail(id,e.getMessage()); } }); break;
                    case "events": io.execute(() -> { try { subscribe(id,p); } catch(Exception e) { fail(id,e.getMessage()); } }); break;
                    case "eventsClose": { closedStreams.add(p.getString("streamId")); Call c=streams.remove(p.getString("streamId")); if(c!=null)c.cancel(); ok(id,json()); break; }
                    case "socketOpen": openSocket(id,p); break;
                    case "socketSend": { WebSocket s=sockets.get(p.getString("socketId")); if(s==null || !s.send(p.getString("text"))) throw new Exception("对话连接不可用"); ok(id,json()); break; }
                    case "socketClose": {
                        String sid=p.getString("socketId"); pendingSockets.remove(sid);
                        WebSocket s=sockets.remove(sid); if(s!=null) { if(s==voiceSocket) voiceOff("connection_closed"); s.close(1000,"client_closed"); }
                        ok(id,json()); break;
                    }
                    case "debugPauseCapture":
                        // Debug-only fixture: inject synthetic speech without mixing live room audio.
                        if((activity.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)==0)throw new Exception("仅限调测版本");
                        stopRecording(); ok(id,json()); break;
                    case "voiceStats": ok(id,json("capturing",capturing,"capturedBytes",capturedBytes.get(),"playedBytes",playedBytes.get(),"socketConnected",voiceSocket!=null)); break;
                    case "voiceStart": microphoneRequest.accept(id); break;
                    case "voiceStop": voiceOff("client_muted"); ok(id,json()); break;
                    case "clearPlayback": clearPlayback(p.optString("reason","gateway_clear")); ok(id,json()); break;
                    default: throw new Exception("未知原生能力");
                }
            } catch(Exception e) { fail(id,e.getMessage()==null?"原生操作失败":e.getMessage()); }
        });
    }
    private Request.Builder request(String path) throws Exception {
        String targetOrigin=origin;
        if(targetOrigin==null || targetOrigin.trim().isEmpty())throw new Exception("请先配置语音服务地址");
        Request.Builder builder=new Request.Builder().url(targetOrigin+EndpointPolicy.path(path)).header("Origin",targetOrigin);
        JSONObject login=vault.load(targetOrigin);
        if(login!=null) builder.header("Authorization",Credentials.basic(login.getString("username"),login.getString("password")));
        return builder;
    }
    private void fetch(String id,JSONObject p) throws Exception {
        Request.Builder b=request(p.getString("path"));
        JSONObject headers=p.optJSONObject("headers");
        if(headers!=null) for(Iterator<String> it=headers.keys();it.hasNext();) {
            String k=it.next(); if(k.equalsIgnoreCase("content-type") || k.equalsIgnoreCase("x-mail-csrf")) b.header(k,headers.getString(k));
        }
        String method=p.optString("method","GET").toUpperCase(Locale.ROOT);
        if(!Arrays.asList("GET","POST","PATCH","DELETE").contains(method)) throw new Exception("不支持该请求方法");
        RequestBody body=Arrays.asList("GET").contains(method)?null:RequestBody.create(p.optString("body",""),MediaType.get("application/json; charset=utf-8"));
        try(Response r=http.newBuilder().readTimeout(30,TimeUnit.SECONDS).build().newCall(b.method(method,body).build()).execute()) {
            JSONObject responseHeaders=json("content-type",r.header("Content-Type",""));
            ok(id,json("status",r.code(),"body",r.body()==null?"":r.body().string(),"headers",responseHeaders));
        }
    }
    private void subscribe(String id,JSONObject p) throws Exception {
        String streamId=p.getString("streamId");
        if(closedStreams.contains(streamId)) { ok(id,json()); return; }
        Call call=http.newCall(request(p.getString("path")).header("Accept","text/event-stream").build());
        streams.put(streamId,call); if(closedStreams.contains(streamId)){streams.remove(streamId,call);call.cancel();ok(id,json());return;} ok(id,json());
        try(Response r=call.execute()) {
            if(!r.isSuccessful() || !r.header("Content-Type","").contains("text/event-stream")) throw new IOException("状态推送连接失败 HTTP "+r.code());
            emit("sse",json("id",streamId,"event","open","data",""));
            BufferedReader reader=new BufferedReader(new InputStreamReader(r.body().byteStream(),java.nio.charset.StandardCharsets.UTF_8));
            SseParser parser=new SseParser((name,data) -> emit("sse",json("id",streamId,"event",name,"data",data)));
            String line;
            while((line=reader.readLine())!=null && !call.isCanceled())parser.line(line);

        } catch(Exception ignored) { }
        finally { if(streams.remove(streamId,call)) emit("sse",json("id",streamId,"event","error","data","")); }
    }
    private void openSocket(String id,JSONObject p) throws Exception {
        String sid=p.getString("socketId"), path=p.getString("path");
        if(!new URI(path).getPath().equals("/api/realtime"))throw new Exception("不支持该对话路径");
        pendingSockets.put(sid,true);
        WebSocket s=http.newWebSocket(request(path).build(),new WebSocketListener() {
            @Override public void onOpen(WebSocket ws,Response response) {
                if(!pendingSockets.containsKey(sid)) { ws.close(1000,"cancelled"); return; }
                sockets.put(sid,ws); voiceSocket=ws; emit("socket",json("id",sid,"event","open"));
            }
            @Override public void onMessage(WebSocket ws,String text) {
                try {
                    JSONObject e=object(text); String type=e.optString("type");
                    if(type.equals("voice.ready")) inputRate=e.optInt("inputSampleRate",16000);
                    if(type.equals("audio.delta")) { if(requested && foreground) queueAudio(e); return; }
                    if(type.equals("audio.done")) { if(requested && foreground) queueEnd(e); return; }
                    if(type.equals("playback.clear")) { clearPlayback(e.optString("reason","gateway_clear")); return; }
                    if(type.equals("voice.deactivated") || type.equals("voice.sleep"))voiceOff("voice_deactivated");
                    if(type.equals("input.suspend")) { stopRecording(); sendEvent("input.suspend.ack",json()); }
                    if(type.equals("input.resume") && requested && foreground) io.execute(() -> startMicrophone(null));
                    emit("socket",json("id",sid,"event","message","text",text));
                } catch(Exception ignored) { emit("socket",json("id",sid,"event","error")); }
            }
            @Override public void onClosing(WebSocket ws,int code,String reason) { ws.close(code,reason); }
            @Override public void onClosed(WebSocket ws,int code,String reason) { closed(ws,code,reason); }
            @Override public void onFailure(WebSocket ws,Throwable t,Response response) { closed(ws,1006,"network_error"); }
            private void closed(WebSocket ws,int code,String reason) {
                pendingSockets.remove(sid); sockets.remove(sid,ws);
                if(voiceSocket==ws) { voiceSocket=null; voiceOff("connection_closed"); }
                emit("socket",json("id",sid,"event","close","code",code,"reason",reason));
            }
        });
        ok(id,json());
    }
    private void sendEvent(String type,JSONObject data) {
        WebSocket s=voiceSocket; if(s==null)return;
        try { data.put("type",type); data.put("event_id","android_"+UUID.randomUUID()); s.send(data.toString()); } catch(Exception ignored) {}
    }
    private synchronized boolean requestVoiceFocus(int gain) {
        if(focus!=null)return true;
        int requestGeneration=++focusGeneration;
        AudioAttributes attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        AudioFocusRequest request=new AudioFocusRequest.Builder(gain).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(change -> handleVoiceFocusChange(requestGeneration, change)).build();
        if(manager.requestAudioFocus(request)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)return false;
        focus=request;
        return true;
    }
    private synchronized void handleVoiceFocusChange(int requestGeneration, int change) {
        if(focus!=null && requestGeneration==focusGeneration && change<0)voiceOff("audio_focus_lost");
    }
    private synchronized void releaseVoiceFocus() {
        if(focus==null)return;
        AudioFocusRequest request=focus;
        focus=null;
        focusGeneration++;
        manager.abandonAudioFocusRequest(request);
    }
    public synchronized void yieldFocusToLocalMedia() {
        if(!requested)return;
        // Our own playback command must not mute the voice session when MediaX takes focus.
        mediaOwnsFocus=true;
        releaseVoiceFocus();
    }
    private synchronized boolean requestReplyFocus() {
        return requested && foreground && requestVoiceFocus(
            mediaOwnsFocus ? AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK : AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
    }
    private synchronized void releaseReplyFocus() {
        if(mediaOwnsFocus)releaseVoiceFocus();
    }
    public synchronized void startMicrophone(String id) {
        if(!foreground || voiceSocket==null) { fail(id,"请保持应用前台并先连接对话服务"); voiceOff("not_ready"); return; }
        if(capturing) { ok(id,json()); return; }
        try {
            if(!mediaOwnsFocus && !requestVoiceFocus(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT))throw new IOException("音频焦点被占用");
            manager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            int size=AudioRecord.getMinBufferSize(inputRate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(size<=0)throw new IOException("设备不支持所需录音采样率");
            recorder=new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,inputRate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(size,inputRate/5));
            if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IOException("录音设备初始化失败");
            if(AcousticEchoCanceler.isAvailable()) { echo=AcousticEchoCanceler.create(recorder.getAudioSessionId()); if(echo!=null)echo.setEnabled(true); }
            if(NoiseSuppressor.isAvailable()) { noise=NoiseSuppressor.create(recorder.getAudioSessionId()); if(noise!=null)noise.setEnabled(true); }
            recorder.startRecording(); capturing=true; requested=true;
            sendEvent("unmute",json());
            final AudioRecord current=recorder;
            io.execute(() -> capture(current)); ok(id,json());
        } catch(Exception e) { fail(id,"无法开启录音："+e.getMessage()); voiceOff("capture_failed"); }
    }
    private void capture(AudioRecord current) {
        byte[] bytes=new byte[Math.max(640,inputRate*2/25)]; long lastLevel=0;
        try {
            while(capturing && current==recorder && foreground) {
                int n=current.read(bytes,0,bytes.length);
                if(n<0)throw new IOException("录音中断"); if(n==0)continue;
                WebSocket s=voiceSocket;
                if(s==null || s.queueSize()>256*1024)throw new IOException("语音网络拥塞，请重新开启");
                capturedBytes.addAndGet(n);
                byte[] frame=Arrays.copyOf(bytes,n);
                sendEvent("input_audio_buffer.append",json("audio",Base64.encodeToString(frame,Base64.NO_WRAP)));
                if(SystemClock.elapsedRealtime()-lastLevel>100) {
                    double sum=0; for(int j=0;j+1<n;j+=2) { double sample=(short)((bytes[j]&255)|(bytes[j+1]<<8))/32768.0; sum+=sample*sample; }
                    emit("level",json("input",Math.min(1,Math.sqrt(sum/Math.max(1,n/2))*8))); lastLevel=SystemClock.elapsedRealtime();
                }
            }
        } catch(Exception e) { if(capturing)voiceOff("capture_failed"); }
    }
    private synchronized void stopRecording() {
        capturing=false; AudioRecord old=recorder; recorder=null;
        if(old!=null) { try { old.stop(); }catch(Exception ignored){} old.release(); }
        if(echo!=null){echo.release();echo=null;} if(noise!=null){noise.release();noise=null;}
    }
    public synchronized void voiceOff(String reason) {
        requested=false; stopRecording(); clearPlayback(reason); sendEvent("mute",json());
        releaseVoiceFocus();
        mediaOwnsFocus=false;
        manager.setMode(AudioManager.MODE_NORMAL);
        emit("voiceOff",json("reason",reason));
    }
    private static final class AudioChunk {
        final byte[] bytes; final String id; final int rate,gen; final boolean end;
        AudioChunk(byte[] b,String i,int r,int g,boolean e){bytes=b;id=i;rate=r;gen=g;end=e;}
    }
    private void queueAudio(JSONObject e) {
        String id=e.optString("responseId");
        synchronized(playbackLock) { if(cancelled.contains(id))return; }
        try {
            if(!playback.offer(new AudioChunk(Base64.decode(e.getString("audio"),Base64.DEFAULT),id,e.optInt("sampleRate",24000),generation,false)))voiceOff("playback_overflow");
        } catch(Exception ignored) { voiceOff("invalid_audio"); }
    }
    private void queueEnd(JSONObject e) { if(!playback.offer(new AudioChunk(new byte[0],e.optString("responseId"),24000,generation,true)))voiceOff("playback_overflow"); }
    private void playLoop() {
        long written=0; int rate=0,activeGeneration=-1;
        while(!destroyed) {
            try {
                AudioChunk c=playback.poll(500,TimeUnit.MILLISECONDS); if(c==null)continue;
                if(c.gen!=generation || !requested || !foreground)continue;
                if(c.end) {
                    long until=SystemClock.elapsedRealtime()+15000;
                    while(c.gen==generation && track!=null && (track.getPlaybackHeadPosition()&0xffffffffL)<written && SystemClock.elapsedRealtime()<until)Thread.sleep(10);
                    synchronized(playbackLock) {
                        if(c.gen==generation && speaking.remove(c.id)) { sendEvent("playback.ended",json("responseId",c.id)); if(c.id.equals(currentPlaybackId))currentPlaybackId=""; }
                    }
                    releaseReplyFocus();
                    continue;
                }
                if(!requestReplyFocus())continue;
                synchronized(playbackLock) {
                    if(c.gen!=generation)continue;
                    if(track==null || rate!=c.rate || activeGeneration!=c.gen) {
                        if(track!=null)track.release();
                        rate=c.rate;activeGeneration=c.gen;written=0;
                        int min=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
                        track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                            .setBufferSizeInBytes(Math.max(min,rate/5)).setTransferMode(AudioTrack.MODE_STREAM).build();
                        track.play();
                    }
                }
                currentPlaybackId=c.id;
                AudioTrack current=track;
                long before=written;
                int offset=0;
                while(offset<c.bytes.length && c.gen==generation) {
                    int n=current.write(c.bytes,offset,Math.min(2048,c.bytes.length-offset),AudioTrack.WRITE_NON_BLOCKING);
                    if(n<0)throw new IOException("播放设备不可用");
                    if(n==0){Thread.sleep(5);continue;} offset+=n;written+=n/2;playedBytes.addAndGet(n);
                    if(!speaking.contains(c.id)) {
                        long until=SystemClock.elapsedRealtime()+200;
                        while(c.gen==generation && (current.getPlaybackHeadPosition()&0xffffffffL)<=before && SystemClock.elapsedRealtime()<until)Thread.sleep(2);
                        synchronized(playbackLock) {
                            if(c.gen==generation && (current.getPlaybackHeadPosition()&0xffffffffL)>before && speaking.add(c.id))sendEvent("playback.started",json("responseId",c.id));
                        }
                    }
                }
            } catch(Exception e) { if(!destroyed)clearPlayback("playback_error"); }
        }
    }
    private void clearPlayback(String reason) {
        synchronized(playbackLock) {
            generation++;
            Set<String> pendingIds=new LinkedHashSet<>(speaking);
            if(!currentPlaybackId.isEmpty())pendingIds.add(currentPlaybackId);
            for(AudioChunk c:playback)if(!c.id.isEmpty())pendingIds.add(c.id);
            for(String id:pendingIds)if(cancelled.add(id))sendEvent("playback.cancelled",json("responseId",id,"reason",reason));
            while(cancelled.size()>512)cancelled.remove(cancelled.iterator().next());
            currentPlaybackId="";
            speaking.clear(); playback.clear();
            if(track!=null) { try {track.pause();track.flush();track.release();}catch(Exception ignored){} track=null; }
        }
        releaseReplyFocus();
    }
    private void closeConnections() {
        pendingSockets.clear(); for(WebSocket ws:sockets.values())ws.close(1000,"endpoint_changed"); sockets.clear(); voiceSocket=null;
        for(Call c:streams.values())c.cancel(); streams.clear();
    }
    public void destroy() { voiceOff("destroyed"); closeConnections(); destroyed=true; control.shutdownNow(); io.shutdownNow(); http.dispatcher().executorService().shutdown(); }
}
