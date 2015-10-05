package ch.uzh.csg.reimbursement.model;

import static ch.uzh.csg.reimbursement.model.ExpenseState.ASSIGNED_TO_FINANCE_ADMIN;
import static ch.uzh.csg.reimbursement.model.ExpenseState.ASSIGNED_TO_PROF;
import static ch.uzh.csg.reimbursement.model.ExpenseState.DRAFT;
import static ch.uzh.csg.reimbursement.model.ExpenseState.PRINTED;
import static ch.uzh.csg.reimbursement.model.ExpenseState.REJECTED;
import static ch.uzh.csg.reimbursement.model.ExpenseState.SIGNED;
import static ch.uzh.csg.reimbursement.model.ExpenseState.TO_BE_ASSIGNED;
import static ch.uzh.csg.reimbursement.model.ExpenseState.TO_SIGN_BY_FINANCE_ADMIN;
import static ch.uzh.csg.reimbursement.model.ExpenseState.TO_SIGN_BY_PROF;
import static ch.uzh.csg.reimbursement.model.ExpenseState.TO_SIGN_BY_USER;
import static ch.uzh.csg.reimbursement.model.Role.FINANCE_ADMIN;
import static ch.uzh.csg.reimbursement.model.Role.PROF;
import static java.util.UUID.randomUUID;
import static javax.persistence.CascadeType.ALL;
import static javax.persistence.EnumType.STRING;
import static javax.persistence.FetchType.EAGER;
import static javax.persistence.GenerationType.IDENTITY;

import java.io.IOException;
import java.util.Date;
import java.util.Set;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.OneToOne;
import javax.persistence.Table;
import javax.persistence.Transient;

import lombok.Getter;
import lombok.Setter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import ch.uzh.csg.reimbursement.model.exception.MaxFileSizeViolationException;
import ch.uzh.csg.reimbursement.model.exception.PdfExportViolationException;
import ch.uzh.csg.reimbursement.model.exception.PdfSignViolationException;
import ch.uzh.csg.reimbursement.model.exception.ServiceException;
import ch.uzh.csg.reimbursement.model.exception.UnexpectedStateException;
import ch.uzh.csg.reimbursement.serializer.UserSerializer;
import ch.uzh.csg.reimbursement.service.ExpenseService;
import ch.uzh.csg.reimbursement.utils.PropertyProvider;
import ch.uzh.csg.reimbursement.view.View;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

@Entity
@Table(name = "Expense")
@Transactional
@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "uid")
public class Expense {

	@Transient
	private final Logger LOG = LoggerFactory.getLogger(ExpenseService.class);

	@Id
	@GeneratedValue(strategy = IDENTITY)
	private int id;

	@JsonView(View.SummaryWithUid.class)
	@Getter
	@Column(nullable = false, updatable = true, unique = true, name = "uid")
	private String uid;

	@JsonView(View.DashboardSummary.class)
	@JsonSerialize(using = UserSerializer.class)
	@Getter
	@Setter
	@ManyToOne
	@JoinColumn(name = "user_id")
	private User user;

	@JsonView(View.DashboardSummary.class)
	@Getter
	@Setter
	@Column(nullable = false, updatable = true, unique = false, name = "date")
	private Date date;

	@JsonView(View.DashboardSummary.class)
	@Getter
	@Enumerated(STRING)
	@Column(nullable = true, updatable = true, unique = false, name = "state")
	private ExpenseState state;

	@JsonView(View.Summary.class)
	@JsonSerialize(using = UserSerializer.class)
	@Getter
	@Setter
	@ManyToOne
	@JoinColumn(name = "finance_admin_id")
	private User financeAdmin;

	@JsonView(View.Summary.class)
	@JsonSerialize(using = UserSerializer.class)
	@Getter
	@Setter
	@ManyToOne
	@JoinColumn(name = "assigned_manager_id")
	private User assignedManager;

	@JsonView(View.DashboardSummary.class)
	@Getter
	@Setter
	@Column(nullable = false, updatable = true, unique = false, name = "accounting")
	private String accounting;

	@JsonView(View.DashboardSummary.class)
	public double getTotalAmount() {
		double totalAmount = 0;
		for (ExpenseItem item : getExpenseItems()) {
			totalAmount += item.getCalculatedAmount();
		}
		return totalAmount;
	}

	@JsonView(View.Summary.class)
	@Getter
	@Setter
	@Column(nullable = true, updatable = true, unique = false, name = "comment")
	private String rejectComment;

	@Getter
	@OneToMany(mappedBy = "expense", fetch = EAGER, orphanRemoval = true)
	private Set<ExpenseItem> expenseItems;

	@OneToOne(cascade = ALL, orphanRemoval = true)
	@JoinColumn(name = "document_id")
	private Document expensePdf;

	public Expense(User user, Date date, User financeAdmin, String accounting, ExpenseState state) {
		setUser(user);
		setDate(date);
		setState(state);
		setFinanceAdmin(financeAdmin);
		setAccounting(accounting);
		this.uid = randomUUID().toString();
	}

	public void updateExpense(Date date, User financeAdmin, String accounting, User assignedManager, ExpenseState state) {
		setDate(date);
		setFinanceAdmin(financeAdmin);
		setAccounting(accounting);
		setAssignedManager(assignedManager);
		setState(state);
	}

	public Document setPdf(MultipartFile multipartFile) {
		// TODO remove PropertyProvider and replace it with @Value values in the
		// calling class of this method.
		// you can find examples in the method Token.isExpired.
		if (this.getExpensePdf() == null) {
			LOG.error("PDF has never been exported");
			throw new PdfExportViolationException();
		} else if (multipartFile.getSize() <= this.getExpensePdf().getFileSize()) {
			LOG.error("File has not been changed");
			throw new PdfSignViolationException();
		} else if (multipartFile.getSize() >= Long.parseLong(PropertyProvider.INSTANCE
				.getProperty("reimbursement.filesize.maxUploadFileSize"))) {
			LOG.error("File too big, allowed: "
					+ PropertyProvider.INSTANCE.getProperty("reimbursement.filesize.maxUploadFileSize") + " actual: "
					+ multipartFile.getSize());
			throw new MaxFileSizeViolationException();
		} else {
			byte[] content = null;
			try {
				content = multipartFile.getBytes();
				expensePdf.updateDocument(multipartFile.getContentType(), multipartFile.getSize(), content);
				goToNextState();
				LOG.debug("The expensePdf has been updated with a signedPdf");

			} catch (IOException e) {
				LOG.error("An IOException has been caught while creating a signature.", e);
				throw new ServiceException();
			}
		}
		return expensePdf;
	}

	public Document setPdf(Document document) {
		return this.expensePdf = document;
	}

	public Document getExpensePdf() {
		return expensePdf;
	}

	public void goToNextState() {

		if (state.equals(DRAFT) && !(user.getRoles().contains(PROF) || user.getRoles().contains(FINANCE_ADMIN))) {
			setState(ASSIGNED_TO_PROF);
		} else if (state.equals(DRAFT) && (user.getRoles().contains(PROF) || user.getRoles().contains(FINANCE_ADMIN))) {
			setState(TO_BE_ASSIGNED);
		} else if (state.equals(ASSIGNED_TO_PROF)) {
			setState(TO_BE_ASSIGNED);
		} else if (state.equals(TO_BE_ASSIGNED)) {
			setState(ASSIGNED_TO_FINANCE_ADMIN);
		} else if (state.equals(ASSIGNED_TO_FINANCE_ADMIN)) {
			setState(TO_SIGN_BY_USER);
		} else if (state.equals(TO_SIGN_BY_USER)) {
			setState(TO_SIGN_BY_PROF);
		} else if (state.equals(TO_SIGN_BY_PROF)) {
			setState(TO_SIGN_BY_FINANCE_ADMIN);
		} else if (state.equals(TO_SIGN_BY_FINANCE_ADMIN)) {
			setState(SIGNED);
		} else if (state.equals(SIGNED)) {
			setState(PRINTED);
		} else {
			LOG.error("Unexpected State");
			throw new UnexpectedStateException();
		}
	}

	public void reject(String comment) {
		setState(REJECTED);
		rejectComment = comment;
	}

	private void setState(ExpenseState state) {
		this.state = state;
	}

	/*
	 * The default constructor is needed by Hibernate, but should not be used at
	 * all.
	 */
	protected Expense() {
	}
}
