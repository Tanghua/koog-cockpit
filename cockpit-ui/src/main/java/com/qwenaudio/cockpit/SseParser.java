package com.qwenaudio.cockpit;
final class SseParser {
    interface Listener { void event(String name,String data); }
    private final Listener listener;
    private String event="message";
    private final StringBuilder data=new StringBuilder();
    SseParser(Listener listener){this.listener=listener;}
    void line(String line) {
        if(line.isEmpty()) {
            if(data.length()>0)listener.event(event,data.substring(0,data.length()-1));
            data.setLength(0);event="message";return;
        }
        if(line.startsWith(":"))return;
        int colon=line.indexOf(':');
        String key=colon<0?line:line.substring(0,colon), value=colon<0?"":line.substring(colon+1);
        if(value.startsWith(" "))value=value.substring(1);
        if(key.equals("event"))event=value;
        if(key.equals("data")) {data.append(value).append('\n'); if(data.length()>2_000_000)throw new IllegalArgumentException("状态消息过大");}
    }
}
