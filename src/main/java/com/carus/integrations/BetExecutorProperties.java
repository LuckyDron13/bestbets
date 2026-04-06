package com.carus.integrations;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "bet-executor")
public class BetExecutorProperties {

    /** Enable/disable sending signals to the remote bet executor. */
    private boolean enabled = false;

    /** Base URL of the bet executor service, e.g. http://192.168.1.10:8081 */
    private String url = "http://localhost:8081";
}
