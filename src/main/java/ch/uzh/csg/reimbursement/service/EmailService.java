package ch.uzh.csg.reimbursement.service;

import static ch.uzh.csg.reimbursement.model.ExpenseState.TO_BE_ASSIGNED;

import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.util.HashSet;
import java.util.Set;

import javax.mail.internet.MimeMessage;
import javax.servlet.ServletContext;

import org.apache.velocity.Template;
import org.apache.velocity.app.VelocityEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.mail.javamail.MimeMessagePreparator;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import ch.uzh.csg.reimbursement.dto.EmailHeaderInfo;
import ch.uzh.csg.reimbursement.dto.ExpenseCountsDto;
import ch.uzh.csg.reimbursement.model.EmailReceiver;
import ch.uzh.csg.reimbursement.model.EmailSendJob;
import ch.uzh.csg.reimbursement.model.EmergencyEmailSendJob;
import ch.uzh.csg.reimbursement.model.Expense;
import ch.uzh.csg.reimbursement.model.ExpenseState;
import ch.uzh.csg.reimbursement.model.NotificationSendJob;
import ch.uzh.csg.reimbursement.model.Role;
import ch.uzh.csg.reimbursement.model.User;
import ch.uzh.csg.reimbursement.repository.EmailReceiverRepositoryProvider;
import ch.uzh.csg.reimbursement.repository.ExpenseRepositoryProvider;
import ch.uzh.csg.reimbursement.repository.UserRepositoryProvider;

@Service
public class EmailService {

	private static final Logger LOG = LoggerFactory.getLogger(EmailService.class);

	@Autowired
	private ServletContext ctx;
	@Autowired
	private ExpenseRepositoryProvider expenseRepoProvider;

	@Autowired
	private EmailReceiverRepositoryProvider emailReceiverProvider;

	@Autowired
	private UserRepositoryProvider userProvider;

	@Autowired
	private JavaMailSender mailSender;

	@Autowired
	private VelocityEngine velocityEngine;

	@Value("${mail.defaultEmailTemplatePath}")
	private String defaultEmailTemplatePath;

	@Value("${mail.notificationEmailTemplatePath}")
	private String notificationEmailTemplatePath;

	@Value("${mail.defaultFromEmail}")
	private String defaultFromEmail;

	@Value("${mail.defaultFromName}")
	private String defaultFromName;

	@Value("${mail.defaultSubject}")
	private String defaultSubject;

	@Value("${mail.emergencyEmailAddress}")
	private String emergencyEmailAddress;

	public void processSendJob(final EmailSendJob sendJob) {
		MimeMessagePreparator preparator = new MimeMessagePreparator() {
			@Override
			public void prepare(MimeMessage mimeMessage) throws Exception {
				final EmailHeaderInfo headerInfo = sendJob.getHeaderInfo();
				MimeMessageHelper message = new MimeMessageHelper(mimeMessage);
				message.setFrom(headerInfo.getFromEmail(), headerInfo.getFromName());
				message.setTo(headerInfo.getToEmail());
				if (headerInfo.isSetCcEmail()) {
					message.setCc(headerInfo.getCcEmail());
				}
				if (headerInfo.isSetBccEmail()) {
					message.setBcc(headerInfo.getBccEmail());
				}
				if (headerInfo.isSetReplyToEmail()) {
					message.setReplyTo(headerInfo.getReplyToEmail());
				}
				message.setSubject(headerInfo.getSubject());

				Template template = velocityEngine.getTemplate( sendJob.getTemplatePath() );
				StringWriter writer = new StringWriter();
				template.merge( sendJob.getContext(), writer );
				String body = writer.toString();
				message.setText(body, true);
			}
		};
		this.mailSender.send(preparator);
		LOG.info("Email sent to: "+sendJob.getHeaderInfo().getToEmail());
	}

	public void sendEmailPdfSet(User emailRecipient) {
		if(!emailReceiverProvider.contains(emailRecipient.getUid())){
			emailReceiverProvider.create(new EmailReceiver(emailRecipient.getUid()));
			LOG.info("User added to the EmailReceiver's list - pdf has to be signed by:"+emailRecipient.getFirstName()+" "+emailRecipient.getLastName()+" roles: "+ emailRecipient.getRoles());
		}else{
			LOG.info("User not added to the list, already present: " + emailRecipient.getRoles());
		}
	}

	public void sendEmailExpenseNewAssigned(User emailRecipient) {
		if(!emailReceiverProvider.contains(emailRecipient.getUid())){
			emailReceiverProvider.create(new EmailReceiver(emailRecipient.getUid()));
			LOG.info("User added to the EmailReceiver's list - expenses has to be checked by:"+emailRecipient.getFirstName()+" "+emailRecipient.getLastName()+"roles: "+ emailRecipient.getRoles());
		}else{
			LOG.info("Email not added the expense send job of:" + emailRecipient.getEmail());
		}
	}

	public void sendEmergencyEmail(Exception ex){
		LOG.info("Message: "+ex.getMessage());
		EmailHeaderInfo headerInfo = new EmailHeaderInfo("SystemEmail", "ReimbursementIFI", emergencyEmailAddress, "[reimbursemente] Your attention is required!");
		EmergencyEmailSendJob emergencyEmailSendJob = new EmergencyEmailSendJob(headerInfo, defaultEmailTemplatePath, ex);

		Template template = velocityEngine.getTemplate( emergencyEmailSendJob.getTemplatePath() );
		StringWriter writer = new StringWriter();
		template.merge( emergencyEmailSendJob.getContext(), writer );
		String body = writer.toString();

		long millis = System.currentTimeMillis();
		String path = ctx.getRealPath("/"+"Emergency"+"Email"+millis+".html");
		LOG.info("Path to this email: "+path);
		try {
			FileWriter fw = new FileWriter(path);
			fw.write(body);
			fw.close();
		} catch (IOException e) {
			e.printStackTrace();
		}

		//for REAL
		//processSendJob(notification);

	}


	@Scheduled(cron="${mail.sendOutEmailsCron}")
	@Async
	public void sendOutEmails(){
		for(EmailReceiver emailReceiver : emailReceiverProvider.findAll()){
			User user = userProvider.findByUid(emailReceiver.getUid());
			ExpenseCountsDto counts = getCountsForUser(user);

			EmailHeaderInfo headerInfo = new EmailHeaderInfo(defaultFromEmail, defaultFromName, user.getEmail(), defaultSubject);
			NotificationSendJob notification = new NotificationSendJob(headerInfo, notificationEmailTemplatePath, user,counts);

			//TODO for testing
			LOG.info("Sent to:" + user.getFirstName());
			LOG.info("number of ownExpensesToSign:"+counts.getNumberOfOwnExpensesToSign());
			LOG.info("number of expensesToAssign:"+counts.getNumberOfExpensesToBeAssigned());
			LOG.info("number of expenseItemsToCheck:"+counts.getNumberOfExpensesToCheck());
			LOG.info("number of expenseToSign:"+counts.getNumberOfExpensesToSign());
			LOG.info("number of expenseToPrint:"+counts.getNumberOfOwnExpensesToPrint());


			Template template = velocityEngine.getTemplate( notification.getTemplatePath() );
			StringWriter writer = new StringWriter();
			template.merge( notification.getContext(), writer );
			String body = writer.toString();

			long millis = System.currentTimeMillis();
			String path = ctx.getRealPath("/"+user.getFirstName()+"Email"+millis+".html");
			LOG.info("Path to this email: "+path);
			try {
				FileWriter fw = new FileWriter(path);
				fw.write(body);
				fw.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
			//TODO  decide what is better: delete one by one or all together at the end of the sending of the emails
			emailReceiverProvider.delete(emailReceiver);

			//for REAL
			//			processSendJob(notification);
		}
		assert emailReceiverProvider.findAll().size() == 0;
		LOG.info("All emails from the send queue have been sent");
	}


	public void sendTestEmail() {
		sendOutEmails();
	}

	private ExpenseCountsDto getCountsForUser(User user){
		if(user.getRoles().contains(Role.FINANCE_ADMIN)){

			//expenses of the finance admin himself
			Set<Expense> ownExpensesToSign = expenseRepoProvider.findAllByStateForUser(ExpenseState.TO_SIGN_BY_USER, user);
			Set<Expense> ownExpensesToPrint = expenseRepoProvider.findAllByStateForUser(ExpenseState.SIGNED, user);

			//finance Admin Check
			Set<Expense> expensesNotAssignedToAnyone = expenseRepoProvider.findAllByStateWithoutUser(TO_BE_ASSIGNED, user);
			Set<Expense> expensesAssignedToFinanceAdmin = expenseRepoProvider.findAllByFinanceAdmin(user);
			Set<Expense> expensesAssignedToFinanceAdminStateToSign = new HashSet<Expense>();
			Set<Expense> expensesAssignedToFinanceAdminStateToCheck = new HashSet<Expense>();
			for(Expense expense : expensesAssignedToFinanceAdmin){
				if(expense.getState().equals(ExpenseState.TO_SIGN_BY_FINANCE_ADMIN)){
					expensesAssignedToFinanceAdminStateToSign.add(expense);
				}else if(expense.getState().equals(ExpenseState.ASSIGNED_TO_FINANCE_ADMIN)){
					expensesAssignedToFinanceAdminStateToCheck.add(expense);
				}
			}
			return new ExpenseCountsDto(expensesAssignedToFinanceAdminStateToCheck.size(),expensesAssignedToFinanceAdminStateToSign.size(), expensesNotAssignedToAnyone.size(), ownExpensesToSign.size(),ownExpensesToPrint.size());
		}else if(user.getRoles().contains(Role.PROF) || user.getRoles().contains(Role.DEPARTMENT_MANAGER) || user.getRoles().contains(Role.HEAD_OF_INSTITUTE)){

			//expenses of the manager himself
			Set<Expense> ownExpensesToSign = expenseRepoProvider.findAllByStateForUser(ExpenseState.TO_SIGN_BY_USER, user);
			Set<Expense> ownExpensesToPrint = expenseRepoProvider.findAllByStateForUser(ExpenseState.SIGNED, user);

			//Manager Checks
			Set<Expense> expensesAssignedToManager = expenseRepoProvider.findAllByAssignedManager(user);
			Set<Expense> expensesAssignedToManagerStateToSign = new HashSet<Expense>();
			Set<Expense> expensesAssignedToManagerStateToCheck = new HashSet<Expense>();
			for(Expense expense : expensesAssignedToManager){
				if(expense.getState().equals(ExpenseState.TO_SIGN_BY_MANAGER)){
					expensesAssignedToManagerStateToSign.add(expense);
				}else if(expense.getState().equals(ExpenseState.ASSIGNED_TO_MANAGER)){
					expensesAssignedToManagerStateToCheck.add(expense);
				}
			}
			return new ExpenseCountsDto(expensesAssignedToManagerStateToCheck.size(),expensesAssignedToManagerStateToSign.size(),0, ownExpensesToSign.size(),ownExpensesToPrint.size());
		}
		else{
			return new ExpenseCountsDto(0, 0,0,expenseRepoProvider.findAllByStateForUser(ExpenseState.TO_SIGN_BY_USER, user).size(), expenseRepoProvider.findAllByStateForUser(ExpenseState.SIGNED, user).size());
		}
	}
}