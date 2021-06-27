package com.explorer.ledger.api.model;

import org.hyperledger.fabric.sdk.Enrollment;

import lombok.Data;

import java.security.PrivateKey;

/**
 * FabricEnrollment class holding the private key and certificate of a Fabric User
 */

@Data
public class FabricEnrollment implements Enrollment {
    private PrivateKey key;
    private String cert;

    public FabricEnrollment() {
    }

    public FabricEnrollment(PrivateKey key, String cert) {
        this.key = key;
        this.cert = cert;
    }

}
