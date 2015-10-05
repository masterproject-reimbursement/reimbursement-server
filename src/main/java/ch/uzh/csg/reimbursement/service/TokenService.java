package ch.uzh.csg.reimbursement.service;

import static ch.uzh.csg.reimbursement.model.TokenType.ATTACHMENT_MOBILE;
import static ch.uzh.csg.reimbursement.model.TokenType.GUEST_MOBILE;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import ch.uzh.csg.reimbursement.model.Token;
import ch.uzh.csg.reimbursement.model.TokenType;
import ch.uzh.csg.reimbursement.model.User;
import ch.uzh.csg.reimbursement.model.exception.TokenExpiredException;
import ch.uzh.csg.reimbursement.model.exception.TokenNotFoundException;
import ch.uzh.csg.reimbursement.repository.TokenRepositoryProvider;

@Service
public class TokenService {

	private final Logger LOG = LoggerFactory.getLogger(TokenService.class);

	@Autowired
	private TokenRepositoryProvider tokenRepository;

	@Autowired
	private UserService userService;

	@Value("${reimbursement.token.epxenseItemAttachmentMobile.expirationInMilliseconds}")
	private int tokenExpirationInMilliseconds;

	public void create(Token token) {
		tokenRepository.create(token);
	}

	public void delete(Token token) {
		tokenRepository.delete(token);
	}

	public Token findByUid(String uid) {
		Token token = tokenRepository.findByUid(uid);

		if (token == null) {
			LOG.debug("Token not found in database with uid: " + uid);
			throw new TokenNotFoundException();
		} else {
			return token;
		}
	}

	public Token findByTypeAndUser(TokenType type, User user) {
		return tokenRepository.findByTypeAndUser(type, user);
	}

	public List<Token> findAll() {
		return tokenRepository.findAll();
	}

	public Token updateToken(Token token, String uid) {
		if (token.isExpired(tokenExpirationInMilliseconds)) {
			// generate new token uid only if it is expired
			token.generateNewUid();
		}
		token.setCreatedToNow();
		token.setContent(uid);
		return token;
	}

	public Token createExpenseItemAttachmentMobileToken(String uid) {
		User user = userService.getLoggedInUser();
		Token token = findByTypeAndUser(ATTACHMENT_MOBILE, user);

		if (token != null) {
			token = updateToken(token, uid);
		} else {
			token = new Token(ATTACHMENT_MOBILE, user, uid);
			create(token);
		}
		return token;
	}

	public String createUniAdminToken(String uid) {
		User user = userService.findByUid("guest");
		Token token = findByTypeAndUser(GUEST_MOBILE, user);

		if (token != null) {
			token = updateToken(token, uid);
		} else {
			token = new Token(GUEST_MOBILE, user, uid);
			create(token);
		}
		return token.getUid();
	}

	public void checkValidity(Token token) {
		if (token == null) {
			throw new TokenNotFoundException();
		}
		if (token.isExpired(tokenExpirationInMilliseconds)) {
			throw new TokenExpiredException();
		}
	}
}
