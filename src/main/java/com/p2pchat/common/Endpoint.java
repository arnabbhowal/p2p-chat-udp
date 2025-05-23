package main.java.com.p2pchat.common;

import org.json.JSONObject;
import java.util.Objects;

public class Endpoint {
    public String ip;
    public int port;
    public String type;

    public Endpoint(String ip, int port, String type) {
        this.ip = ip;
        this.port = port;
        this.type = type;
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        obj.put("ip", ip);
        obj.put("port", port);
        obj.put("type", type);
        return obj;
    }

    public static Endpoint fromJson(JSONObject obj) {
        if (obj == null || !obj.has("ip") || !obj.has("port") || !obj.has("type")) {
            return null;
        }
        try {
            String ip = obj.getString("ip");
            int port = obj.getInt("port");
            String type = obj.getString("type");
            if (ip != null && !ip.isEmpty() && type != null && !type.isEmpty() && port > 0 && port <= 65535) {
                 return new Endpoint(ip, port, type);
            } else {
                 System.err.println("[!] Invalid data in Endpoint JSON: " + obj.toString());
                 return null;
            }
        } catch (Exception e) {
             System.err.println("[!] Error parsing Endpoint from JSON: " + obj.toString() + " - " + e.getMessage());
            return null;
        }
    }

    @Override
    public String toString() {
        return type + " " + ip + ":" + port;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Endpoint endpoint = (Endpoint) o;
        return port == endpoint.port &&
               Objects.equals(ip, endpoint.ip) &&
               Objects.equals(type, endpoint.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ip, port, type);
    }
}