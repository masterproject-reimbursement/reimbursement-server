package ch.uzh.csg.reimbursement.model;

import static ch.uzh.csg.reimbursement.model.ExpenseItemState.INITIAL;
import static ch.uzh.csg.reimbursement.model.ExpenseItemState.SUCCESFULLY_CREATED;
import static javax.persistence.CascadeType.ALL;
import static javax.persistence.EnumType.STRING;
import static javax.persistence.GenerationType.IDENTITY;

import java.io.IOException;
import java.util.Date;
import java.util.UUID;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.OneToOne;
import javax.persistence.Table;
import javax.persistence.Transient;

import lombok.Getter;
import lombok.Setter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import ch.uzh.csg.reimbursement.model.exception.ExpenseItemAttachmentNotFoundException;
import ch.uzh.csg.reimbursement.model.exception.ServiceException;
import ch.uzh.csg.reimbursement.model.exception.SignatureMaxFileSizeViolationException;
import ch.uzh.csg.reimbursement.model.exception.SignatureMinFileSizeViolationException;
import ch.uzh.csg.reimbursement.serializer.CostCategorySerializer;
import ch.uzh.csg.reimbursement.utils.PropertyProvider;
import ch.uzh.csg.reimbursement.view.View;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

@Entity
@Table(name = "ExpenseItem")
@Transactional
@JsonIgnoreProperties({ "expenseItemAttachment" })
public class ExpenseItem {

	@Transient
	private final Logger LOG = LoggerFactory.getLogger(ExpenseItem.class);

	@Id
	@GeneratedValue(strategy = IDENTITY)
	private int id;

	@JsonView(View.SummaryWithUid.class)
	@Getter
	@Setter
	@Column(nullable = false, updatable = true, unique = false, name = "uid")
	private String uid;

	@JsonView(View.Summary.class)
	@Getter
	@Setter
	@ManyToOne(optional = false, cascade = { CascadeType.PERSIST, CascadeType.REFRESH, CascadeType.MERGE })
	@JoinColumn(name = "expense_id")
	private Expense expense;

	@JsonView(View.Summary.class)
	@Getter
	@Setter
	@Column(nullable = false, updatable = true, unique = false, name = "date")
	private Date date;

	@JsonView(View.Summary.class)
	@Getter
	@Setter
	@Enumerated(STRING)
	@Column(nullable = false, updatable = true, unique = false, name = "state")
	private ExpenseItemState state;

	@JsonView(View.Summary.class)
	@Getter
	@Setter
	@Column(nullable = true, updatable = true, unique = false, name = "original_amount")
	private double originalAmount;

	@JsonView(View.Summary.class)
	@Getter
	@Setter
	@Column(nullable = true, updatable = true, unique = false, name = "calculated_amount")
	private double calculatedAmount;

	@JsonView(View.Summary.class)
	@JsonSerialize(using = CostCategorySerializer.class)
	@Getter
	@Setter
	@ManyToOne(optional = false, cascade = { CascadeType.PERSIST, CascadeType.REFRESH, CascadeType.MERGE })
	@JoinColumn(name = "cost_category_id")
	private CostCategory costCategory;

	@JsonView(View.Summary.class)
	@Getter
	@Setter
	@Column(nullable = true, updatable = true, unique = false, name = "explanation")
	private String explanation;

	@JsonView(View.Summary.class)
	@Getter
	@Setter
	@Column(nullable = true, updatable = true, unique = false, name = "currency")
	private String currency;

	@Getter
	@Setter
	@Column(nullable = true, updatable = true, unique = false, name = "exchange_rate")
	private double exchangeRate;

	@JsonView(View.Summary.class)
	@Getter
	@Setter
	@Column(nullable = true, updatable = true, unique = false, name = "project")
	private String project;

	@JsonView(View.Summary.class)
	@OneToOne(cascade = ALL, orphanRemoval = true)
	@JoinColumn(name = "expense_item_attachment_id")
	private ExpenseItemAttachment expenseItemAttachment;

	public ExpenseItemAttachment setExpenseItemAttachment(MultipartFile multipartFile) {
		// TODO remove PropertyProvider and replace it with @Value values in the
		// calling class of this method.
		// you can find examples in the method Token.isExpired.
		if (multipartFile.getSize() <= Long.parseLong(PropertyProvider.INSTANCE
				.getProperty("reimbursement.filesize.minExpenseItemAttachmentFileSize"))) {
			LOG.error("File to small, allowed: "
					+ PropertyProvider.INSTANCE.getProperty("reimbursement.filesize.minExpenseItemAttachmentFileSize")
					+ " actual: " + multipartFile.getSize());
			throw new SignatureMinFileSizeViolationException();
		} else if (multipartFile.getSize() >= Long.parseLong(PropertyProvider.INSTANCE
				.getProperty("reimbursement.filesize.maxExpenseItemAttachmentFileSize"))) {
			LOG.error("File to big, allowed: "
					+ PropertyProvider.INSTANCE.getProperty("reimbursement.filesize.maxExpenseItemAttachmentFileSize")
					+ " actual: " + multipartFile.getSize());
			throw new SignatureMaxFileSizeViolationException();
		} else {
			byte[] content = null;
			try {
				content = multipartFile.getBytes();
				expenseItemAttachment = new ExpenseItemAttachment(multipartFile.getContentType(),
						multipartFile.getSize(), content);
			} catch (IOException e) {
				LOG.error("An IOException has been caught while creating a signature.", e);
				throw new ServiceException();
			}
		}
		return expenseItemAttachment;
	}

	public ExpenseItemAttachment getExpenseItemAttachment() {
		if (expenseItemAttachment == null) {
			LOG.error("No expenseItemAttachment found for the expenseItem with uid: " + this.uid);
			throw new ExpenseItemAttachmentNotFoundException();
		}
		return expenseItemAttachment;
	}

	public ExpenseItem(Date date, CostCategory costCategory, String explanation, String currency, double exchangeRate,
			double originalAmount, double calculatedAmount, String project, Expense expense) {
		this.uid = UUID.randomUUID().toString();
		setState(INITIAL);
		setDate(date);
		setCostCategory(costCategory);
		setExplanation(explanation);
		setCurrency(currency);
		setExchangeRate(exchangeRate);
		setOriginalAmount(originalAmount);
		setCalculatedAmount(calculatedAmount);
		setProject(project);
		setExpense(expense);
	}

	public void updateExpenseItem(Date date, CostCategory costCategory, String reason, String currency,
			double exchangeRate, double originalAmount, double calculatedAmount, String costCenter) {
		setState(SUCCESFULLY_CREATED);
		setDate(date);
		setCostCategory(costCategory);
		setExplanation(reason);
		setCurrency(currency);
		setExchangeRate(exchangeRate);
		setOriginalAmount(originalAmount);
		setCalculatedAmount(calculatedAmount);
		setProject(costCenter);
	}

	/*
	 * The default constructor is needed by Hibernate, but should not be used at
	 * all.
	 */
	protected ExpenseItem() {
	}
}
