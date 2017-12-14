// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.controller.api.integration.athenz;

import com.yahoo.vespa.hosted.controller.api.identifiers.AthenzDomain;
import com.yahoo.vespa.hosted.controller.api.identifiers.UserId;

/**
 * @author bjorncs
 */
public class AthenzUtils {

    private AthenzUtils() {}

    public static final AthenzDomain USER_PRINCIPAL_DOMAIN = new AthenzDomain("user");
    public static final AthenzDomain SCREWDRIVER_DOMAIN = new AthenzDomain("cd.screwdriver.project");
    public static final AthenzService ZMS_ATHENZ_SERVICE = new AthenzService("sys.auth", "zms");

    public static AthenzIdentity createAthenzIdentity(AthenzDomain domain, String identityName) {
        if (domain.equals(USER_PRINCIPAL_DOMAIN)) {
            return AthenzUser.fromUserId(new UserId(identityName));
        } else {
            return new AthenzService(domain, identityName);
        }
    }

    public static AthenzIdentity createAthenzIdentity(String fullName) {
        int domainIdentityNameSeparatorIndex = fullName.lastIndexOf('.');
        if (domainIdentityNameSeparatorIndex == -1
                || domainIdentityNameSeparatorIndex == 0
                || domainIdentityNameSeparatorIndex == fullName.length() - 1) {
            throw new IllegalArgumentException("Invalid Athenz identity: " + fullName);
        }
        AthenzDomain domain = new AthenzDomain(fullName.substring(0, domainIdentityNameSeparatorIndex));
        String identityName = fullName.substring(domainIdentityNameSeparatorIndex + 1, fullName.length());
        return createAthenzIdentity(domain, identityName);
    }

}
