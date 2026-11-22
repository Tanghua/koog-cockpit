package com.qwenaudio.cockpit;
import java.net.*;
import java.util.*;
final class EndpointPolicy {
    static String origin(String text) throws Exception {
        URI u=new URI(text.trim());
        if(!Arrays.asList("http","https").contains(u.getScheme()) || u.getHost()==null || u.getRawUserInfo()!=null || u.getRawQuery()!=null || u.getRawFragment()!=null
            || !(u.getPath().isEmpty() || u.getPath().equals("/")) || u.getPort()>65535)throw new Exception("请输入协议、主机和有效端口，不附带路径或凭据");
        if(u.getScheme().equals("http")) {
            // Cleartext is an explicit numeric LAN address, never a DNS target.
            if(!u.getHost().matches("[0-9.]+"))throw new Exception("局域网 HTTP 请使用 Mac 的 IPv4 地址");
            InetAddress ip=InetAddress.getByName(u.getHost());
            if(!ip.isSiteLocalAddress() && !ip.isLoopbackAddress())throw new Exception("HTTP 仅支持局域网地址，公网请使用 HTTPS");
        }
        return u.getScheme()+"://"+u.getRawAuthority();
    }
    static String path(String path) throws Exception {
        URI u=new URI(path);
        if(u.isAbsolute() || u.getRawAuthority()!=null || u.getFragment()!=null || !path.startsWith("/api/") || path.contains("\\")
            || path.toLowerCase(Locale.ROOT).matches(".*%(2e|2f|5c).*"))throw new Exception("请求路径不受支持");
        String normalized=u.normalize().getPath();
        if(!normalized.startsWith("/api/") || normalized.startsWith("/api/workbuddy-mail/authorize"))throw new Exception("请求路径不受支持");
        return path;
    }
}
