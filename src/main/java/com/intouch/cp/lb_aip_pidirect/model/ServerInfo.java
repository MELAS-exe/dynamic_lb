package com.intouch.cp.lb_aip_pidirect.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ServerInfo {

    private String id;
    private String host;
    private String port;
    private String name;
    private boolean enabled = true;

    public ServerInfo(String id, String host, String port, String name) {
        this.id = id;
        this.host = host;
        this.port = port;
        this.name = name;
        this.enabled = true;
    }

    public String getAddress() {
        return host + ":" + port;
    }

    public String getFullAddress() {
        return host + ":" + port;
    }
}