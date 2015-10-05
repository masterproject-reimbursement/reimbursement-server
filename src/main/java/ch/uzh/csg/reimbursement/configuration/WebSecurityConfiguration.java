package ch.uzh.csg.reimbursement.configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.multipart.commons.CommonsMultipartResolver;

import ch.uzh.csg.reimbursement.application.ldap.LdapUserDetailsAuthoritiesPopulator;
import ch.uzh.csg.reimbursement.security.CsrfHeaderFilter;
import ch.uzh.csg.reimbursement.security.FormLoginFailureHandler;
import ch.uzh.csg.reimbursement.security.FormLoginSuccessHandler;
import ch.uzh.csg.reimbursement.security.HttpAuthenticationEntryPoint;
import ch.uzh.csg.reimbursement.security.HttpLogoutSuccessHandler;
import ch.uzh.csg.reimbursement.security.ResourceAccessDeniedHandler;

import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class WebSecurityConfiguration extends WebSecurityConfigurerAdapter {

	private final Logger LOG = LoggerFactory.getLogger(WebSecurityConfiguration.class);

	@Autowired
	private HttpAuthenticationEntryPoint authenticationEntryPoint;

	@Autowired
	private ResourceAccessDeniedHandler accessDeniedHandler;

	@Autowired
	private FormLoginSuccessHandler authSuccessHandler;

	@Autowired
	private FormLoginFailureHandler authFailureHandler;

	@Autowired
	private HttpLogoutSuccessHandler logoutSuccessHandler;

	@Autowired
	private UserDetailsService userDetailsService;

	@Value("${reimbursement.filesize.maxUploadFileSize}")
	private long maxUploadFileSize;

	@Value("${reimbursement.build.developmentMode}")
	private boolean isInDevelopmentMode;

	@Value("${reimbursement.ldap.url}")
	private String ldapUrl;

	@Value("${reimbursement.ldap.base}")
	private String ldapBase;

	/* JSON - Object mapper for use in the authHandlers */
	@Bean
	public ObjectMapper objectMapper() {
		return new ObjectMapper();
	}

	/* Enables File Upload through REST */
	@Bean
	public CommonsMultipartResolver filterMultipartResolver() {
		CommonsMultipartResolver resolver = new CommonsMultipartResolver();
		resolver.setMaxUploadSize(maxUploadFileSize);
		return resolver;
	}

	/* Token Repo for use with CsrfHeaderFilter */
	private CsrfTokenRepository csrfTokenRepository() {
		HttpSessionCsrfTokenRepository repository = new HttpSessionCsrfTokenRepository();
		repository.setHeaderName("X-XSRF-TOKEN");
		return repository;
	}

	@Override
	protected void configure(HttpSecurity http) throws Exception {
		http.csrf()
		.csrfTokenRepository(csrfTokenRepository())
		.and()
		.addFilterAfter(new CsrfHeaderFilter(), CsrfFilter.class);

		http.exceptionHandling()
		.authenticationEntryPoint(authenticationEntryPoint)
		.accessDeniedHandler(accessDeniedHandler)
		.and().authorizeRequests()
		// allow CORS's options preflight
		.antMatchers(HttpMethod.OPTIONS,"/**").permitAll()
		// allow specific rest resources
		.antMatchers("/public/**").permitAll()
		.antMatchers("/api-docs/**", "/swagger-ui/**").permitAll()
		// block everything else
		.anyRequest().fullyAuthenticated()
		.and()
		.formLogin().permitAll()
		.loginProcessingUrl("/login")
		.successHandler(authSuccessHandler)
		.failureHandler(authFailureHandler)
		.and()
		.logout()
		//		check if this is needed. only logged in users should be able to logout :)
		//		.permitAll()
		.logoutUrl("/logout")
		.logoutSuccessHandler(logoutSuccessHandler)
		.and()
		.sessionManagement().maximumSessions(1);
	}

	@Autowired
	public void configureGlobal(AuthenticationManagerBuilder auth) throws Exception {
		// TODO Chrigi remove if not used anymore
		// Howto link: https://github.com/spring-projects/spring-security-javaconfig/blob/master/spring-security-javaconfig/src/test/groovy/org/springframework/security/config/annotation/authentication/ldap/NamespaceLdapAuthenticationProviderTestsConfigs.java
		//			auth.
		//			ldapAuthentication()
		//			// .userDnPattern only used for direct binding to the user -> userSearchFilter for searching
		//			.userDnPatterns("uid={0}")
		//			.contextSource()
		//			.url("ldap://ldap.forumsys.com:389/dc=example,dc=com");

		if(isInDevelopmentMode) {
			LOG.info("Development Mode: Local LDAP server will be started for the authentication. The user database is remotely loaded.");

			auth.ldapAuthentication()
			.ldapAuthoritiesPopulator(new LdapUserDetailsAuthoritiesPopulator(userDetailsService))
			.userSearchFilter("uid={0}")
			.groupSearchBase("ou=Groups")
			.contextSource()
			.ldif("classpath:development-server.ldif")
			.root(ldapBase);
		}
		else {
			LOG.info("Production Mode: Remote LDAP server is used for authentication and and also for the user database.");

			auth.ldapAuthentication()
			.userDnPatterns("uid={0}")
			.contextSource()
			.url(ldapUrl)
			.root(ldapBase);
		}
	}
}
