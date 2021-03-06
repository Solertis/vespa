// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.service.health;

import com.yahoo.config.model.api.ApplicationInfo;
import com.yahoo.config.model.api.HostInfo;
import com.yahoo.config.model.api.PortInfo;
import com.yahoo.config.model.api.ServiceInfo;
import com.yahoo.config.provision.HostName;
import com.yahoo.vespa.service.duper.ZoneApplication;
import com.yahoo.vespa.service.executor.RunletExecutor;
import com.yahoo.vespa.service.model.ApplicationInstanceGenerator;
import com.yahoo.vespa.service.model.ServiceId;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author hakonhall
 */
public class StateV1HealthModel implements AutoCloseable {
    private static final String PORT_TAG_STATE = "STATE";
    private static final String PORT_TAG_HTTP = "HTTP";

    /** Port tags implying /state/v1/health is served on HTTP. */
    public static final List<String> HTTP_HEALTH_PORT_TAGS = Arrays.asList(PORT_TAG_HTTP, PORT_TAG_STATE);
    private final Duration targetHealthStaleness;
    private final Duration requestTimeout;
    private final Duration connectionKeepAlive;
    private final RunletExecutor executor;
    private final boolean monitorTenantHostHealth;

    StateV1HealthModel(Duration targetHealthStaleness,
                       Duration requestTimeout,
                       Duration connectionKeepAlive,
                       RunletExecutor executor,
                       boolean monitorTenantHostHealth) {
        this.targetHealthStaleness = targetHealthStaleness;
        this.requestTimeout = requestTimeout;
        this.connectionKeepAlive = connectionKeepAlive;
        this.executor = executor;
        this.monitorTenantHostHealth = monitorTenantHostHealth;
    }

    Map<ServiceId, HealthEndpoint> extractHealthEndpoints(ApplicationInfo application) {
        Map<ServiceId, HealthEndpoint> endpoints = new HashMap<>();

        boolean isZoneApplication = application.getApplicationId().equals(ZoneApplication.getApplicationId());

        for (HostInfo hostInfo : application.getModel().getHosts()) {
            HostName hostname = HostName.from(hostInfo.getHostname());
            for (ServiceInfo serviceInfo : hostInfo.getServices()) {

                if (monitorTenantHostHealth && isZoneApplication &&
                        !ZoneApplication.isNodeAdminServiceInfo(application.getApplicationId(), serviceInfo)) {
                    // Only the node admin/host admin cluster of the zone application should be monitored
                    // TODO: Move the node admin cluster out to a separate infrastructure application
                    continue;
                }

                ServiceId serviceId = ApplicationInstanceGenerator.getServiceId(application, serviceInfo);
                for (PortInfo portInfo : serviceInfo.getPorts()) {
                    if (portInfo.getTags().containsAll(HTTP_HEALTH_PORT_TAGS)) {
                        StateV1HealthEndpoint endpoint = new StateV1HealthEndpoint(
                                serviceId,
                                hostname,
                                portInfo.getPort(),
                                targetHealthStaleness,
                                requestTimeout,
                                connectionKeepAlive,
                                executor);
                        endpoints.put(serviceId, endpoint);
                        break; // Avoid >1 endpoints per serviceId
                    }
                }
            }
        }

        return endpoints;
    }

    @Override
    public void close() {
        executor.close();
    }
}
