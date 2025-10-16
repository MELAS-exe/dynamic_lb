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
    private String name;
    private boolean enabled = true;

    public ServerInfo(String id, String host, String name) {
        this.id = id;
        this.host = host;
        this.name = name;
        this.enabled = true;
    }

    public String getAddress() {
        return host;
    }

    public String getFullAddress() {
        return host;
    }
}