package com.explorer.ledger.api.model;

import org.hyperledger.fabric.sdk.Enrollment;
import org.hyperledger.fabric.sdk.User;

import lombok.Data;

import java.util.Set;

/**
 * FabricUser class holding details of a Fabric User
 *
 */
@Data
public class FabricUser implements User {

	private String name;
	private Set<String> roles;
	private String account;
	private String affiliation;
	private Enrollment enrollment;
	private String mspId;

	public FabricUser(String name, String affiliation, String mspId, Enrollment enrollment) {
		this.name = name;
		this.affiliation = affiliation;
		this.enrollment = enrollment;
		this.mspId = mspId;
	}

}
