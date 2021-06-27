package com.explorer.ledger.api.model;

import java.util.List;

import lombok.Data;

@Data
public class User {
	
	private String firstName;
	
	private String lastName;
	
	private String email;
	
	private String phone;
	
	private String userName;
	
	private List<String> groups;

}
