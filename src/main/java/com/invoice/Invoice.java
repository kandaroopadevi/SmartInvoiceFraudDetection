package com.invoice;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
public class Invoice {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false) private String invoiceNumber;
    @Column(nullable=false) private String vendor;
    private String gstin;
    private String currency="INR";
    private String paymentStatus="Unknown";
    private boolean disputed;
    private LocalDate invoiceDate;
    @Column(precision=14,scale=2) private BigDecimal taxableAmount=BigDecimal.ZERO;
    @Column(precision=14,scale=2) private BigDecimal gstAmount=BigDecimal.ZERO;
    @Column(precision=6,scale=2) private BigDecimal gstRate=BigDecimal.ZERO;
    @Column(precision=14,scale=2) private BigDecimal totalAmount=BigDecimal.ZERO;
    private Integer quantity;
    private String poNumber;
    private Integer poQuantity;
    @Column(precision=14,scale=2) private BigDecimal poAmount;
    private String grnNumber;
    private Integer receivedQuantity;
    @Column(precision=14,scale=2) private BigDecimal receivedAmount;
    private Integer riskScore=0;
    private String riskLevel="LOW";
    @Column(length=1200) private String reasons="No issues detected";
    private String reviewStatus="Pending";

    protected Invoice() {}
    public Invoice(String number,String vendor,String gstin,LocalDate date,BigDecimal taxable,BigDecimal tax,BigDecimal rate,BigDecimal total,Integer qty) {
        this.invoiceNumber=number;this.vendor=vendor;this.gstin=gstin;this.invoiceDate=date;this.taxableAmount=taxable;this.gstAmount=tax;this.gstRate=rate;this.totalAmount=total;this.quantity=qty;
    }
    public Long getId(){return id;} public String getInvoiceNumber(){return invoiceNumber;} public String getVendor(){return vendor;} public String getGstin(){return gstin;} public LocalDate getInvoiceDate(){return invoiceDate;} public BigDecimal getTaxableAmount(){return taxableAmount;} public BigDecimal getGstAmount(){return gstAmount;} public BigDecimal getGstRate(){return gstRate;} public BigDecimal getTotalAmount(){return totalAmount;} public Integer getQuantity(){return quantity;} public Integer getRiskScore(){return riskScore;} public String getRiskLevel(){return riskLevel;} public String getReasons(){return reasons;} public String getReviewStatus(){return reviewStatus;}
    public String getCurrency(){return currency;} public String getPaymentStatus(){return paymentStatus;}
    public boolean isDisputed(){return disputed;}
    public String getPoNumber(){return poNumber;} public Integer getPoQuantity(){return poQuantity;} public BigDecimal getPoAmount(){return poAmount;} public String getGrnNumber(){return grnNumber;} public Integer getReceivedQuantity(){return receivedQuantity;} public BigDecimal getReceivedAmount(){return receivedAmount;}
    public void setMatching(String po,Integer poQty,BigDecimal poAmt,String grn,Integer receivedQty,BigDecimal receivedAmt){this.poNumber=po;this.poQuantity=poQty;this.poAmount=poAmt;this.grnNumber=grn;this.receivedQuantity=receivedQty;this.receivedAmount=receivedAmt;}
    public void setSourceDetails(String currency,String paymentStatus){if(currency!=null&&!currency.isBlank())this.currency=currency.trim().toUpperCase();if(paymentStatus!=null&&!paymentStatus.isBlank())this.paymentStatus=paymentStatus;}
    public void setDisputed(boolean disputed){this.disputed=disputed;}
    public void setRisk(int score,String level,String why){riskScore=score;riskLevel=level;reasons=why;}
    public void setReviewStatus(String status){reviewStatus=status;}
}
