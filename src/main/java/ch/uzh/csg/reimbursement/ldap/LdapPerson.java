package ch.uzh.csg.reimbursement.ldap;

import lombok.Data;

@Data
public class LdapPerson {

	private String firstName;
	private String lastName;
	private String uid;
	private String email;
	private String manager;

}