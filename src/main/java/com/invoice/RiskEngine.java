package com.invoice;

import org.springframework.stereotype.Service;
import java.math.*;
import java.time.LocalDate;
import java.util.*;

@Service public class RiskEngine {
    public void evaluate(List<Invoice> all) {
        Map<String,BigDecimal> vendorTotals=new HashMap<>();Map<String,Integer> vendorCounts=new HashMap<>();Map<String,Integer> duplicateCounts=new HashMap<>();
        for(Invoice i:all){String vendor=norm(i.getVendor());vendorTotals.merge(vendor,i.getTotalAmount(),BigDecimal::add);vendorCounts.merge(vendor,1,Integer::sum);duplicateCounts.merge(vendor+'|'+norm(i.getInvoiceNumber()),1,Integer::sum);}
        for(Invoice i:all){int score=0;List<String> reasons=new ArrayList<>();
            String vendor=norm(i.getVendor());int count=vendorCounts.getOrDefault(vendor,0);
            boolean duplicate=duplicateCounts.getOrDefault(vendor+'|'+norm(i.getInvoiceNumber()),0)>1;
            if(duplicate){score+=30;reasons.add("Duplicate vendor and invoice number");}
            BigDecimal expected=i.getTaxableAmount().multiply(i.getGstRate()).divide(new BigDecimal("100"),2,RoundingMode.HALF_UP);
            if(expected.subtract(i.getGstAmount()).abs().compareTo(new BigDecimal("1.00"))>0){score+=20;reasons.add("GST amount differs from the stated rate");}
            if(i.getTaxableAmount().add(i.getGstAmount()).subtract(i.getTotalAmount()).abs().compareTo(new BigDecimal("1.00"))>0){score+=15;reasons.add("Invoice total does not equal taxable amount plus GST");}
            if(count>=3){BigDecimal avg=vendorTotals.get(vendor).subtract(i.getTotalAmount()).divide(BigDecimal.valueOf(Math.max(1,count-1)),2,RoundingMode.HALF_UP);if(avg.signum()>0&&i.getTotalAmount().compareTo(avg.multiply(new BigDecimal("3")))>0){score+=15;reasons.add("Amount is more than 3× this vendor’s average");}}
            if(i.getInvoiceDate()!=null&&(i.getInvoiceDate().getDayOfWeek().getValue()>=6)){score+=10;reasons.add("Invoice dated on a weekend");}
            if(i.getTotalAmount().signum()<=0){score+=25;reasons.add("Invoice total is zero or negative");}
            if(i.getInvoiceDate()!=null&&i.getInvoiceDate().isAfter(LocalDate.now())){score+=15;reasons.add("Invoice date is in the future");}
            if(i.getGstRate().signum()<0||i.getGstRate().compareTo(new BigDecimal("100"))>0){score+=20;reasons.add("GST rate is outside the valid 0–100% range");}
            if(i.getTotalAmount().compareTo(new BigDecimal("100000"))>=0&&i.getTotalAmount().remainder(new BigDecimal("10000")).signum()==0){score+=5;reasons.add("Large round-number invoice amount");}
            if(i.getPoNumber()!=null && !i.getPoNumber().isBlank()){
                boolean mismatch=false;
                if(i.getPoQuantity()!=null && !Objects.equals(i.getQuantity(),i.getPoQuantity())) mismatch=true;
                if(i.getReceivedQuantity()!=null && !Objects.equals(i.getQuantity(),i.getReceivedQuantity())) mismatch=true;
                if(i.getPoAmount()!=null && i.getTotalAmount().subtract(i.getPoAmount()).abs().compareTo(new BigDecimal("1.00"))>0) mismatch=true;
                if(i.getReceivedAmount()!=null && i.getTotalAmount().subtract(i.getReceivedAmount()).abs().compareTo(new BigDecimal("1.00"))>0) mismatch=true;
                if(mismatch){score+=25;reasons.add("Three-way match mismatch across invoice, PO and/or goods receipt");}
            }
            if(i.getGstin()!=null && !i.getGstin().isBlank() && !i.getGstin().toUpperCase(Locale.ROOT).matches("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][A-Z0-9]Z[A-Z0-9]$")){score+=10;reasons.add("GSTIN format is invalid (registration status not checked)");}
            if(i.isDisputed()){score+=10;reasons.add("Invoice is marked disputed; auditor review recommended");}
            if(duplicate&&count>=3){score+=10;reasons.add("Cross-rule adjustment: duplicate plus established vendor history");}
            score=Math.min(100,score);String level=score>=51?"HIGH":score>=21?"MEDIUM":"LOW";
            i.setRisk(score,level,reasons.isEmpty()?"No issues detected":String.join("; ",reasons));
        }
    }
    private String norm(String s){return s==null?"":s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]","");}
}
