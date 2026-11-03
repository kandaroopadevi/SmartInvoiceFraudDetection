package com.invoice;

import org.apache.commons.csv.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service public class InvoiceService {
    private static final List<String> FIELDS=List.of("invoiceNumber","vendor","invoiceDate","totalAmount","taxableAmount","gstAmount","gstRate","quantity","gstin","currency","paymentStatus","settledDate","disputed","poNumber","poQuantity","poAmount","grnNumber","receivedQuantity","receivedAmount");
    private static final Set<String> REQUIRED=Set.of("invoiceNumber","vendor","invoiceDate","totalAmount");
    private final InvoiceRepository invoices; private final AuditLogRepository logs; private final RiskEngine risk;
    private final Map<String,PendingUpload> pending=new ConcurrentHashMap<>();
    private volatile byte[] latestExcel;
    private volatile List<Invoice> latestInvoices=List.of();

    public InvoiceService(InvoiceRepository invoices,AuditLogRepository logs,RiskEngine risk){this.invoices=invoices;this.logs=logs;this.risk=risk;}

    public UploadResult importOrPrepare(MultipartFile file) throws IOException {
        if(file.isEmpty())throw new IllegalArgumentException("Choose a non-empty CSV or Excel file.");
        byte[] bytes=file.getBytes();String filename=Objects.toString(file.getOriginalFilename(),"upload.csv");
        Dataset data=readDataset(bytes,filename);Map<String,String> mapping=detectMapping(data.headers);
        if(hasRequired(mapping))return process(data,filename,mapping);
        pending.entrySet().removeIf(e->e.getValue().createdAt.isBefore(Instant.now().minusSeconds(3600)));
        if(pending.size()>=10)pending.clear();
        String id=UUID.randomUUID().toString();pending.put(id,new PendingUpload(bytes,filename,data.headers,Instant.now()));
        UploadResult result=new UploadResult();result.needsMapping=true;result.uploadId=id;result.headers=data.headers;result.mapping=mapping;
        return result;
    }

    public UploadResult importWithMapping(String uploadId,Map<String,String> selected) throws IOException {
        PendingUpload upload=pending.remove(uploadId);if(upload==null)throw new IllegalArgumentException("This upload preview expired. Upload the file again.");
        Dataset data=readDataset(upload.bytes,upload.filename);Map<String,String> mapping=new HashMap<>();
        for(String field:FIELDS){String column=selected.get(field);if(column!=null&&!column.isBlank()&&data.headers.contains(column))mapping.put(field,column);}
        if(!hasRequired(mapping))throw new IllegalArgumentException("Map invoice number, vendor/customer, invoice date, and total amount before importing.");
        return process(data,upload.filename,mapping);
    }

    public boolean hasLatestExcel(){return latestExcel!=null&&latestExcel.length>0;}
    public byte[] latestExcel(){return latestExcel==null?new byte[0]:latestExcel.clone();}
    public List<Invoice> latestInvoices(){return latestInvoices;}
    public void updateLatestReview(Long id,String status){latestInvoices.stream().filter(i->Objects.equals(i.getId(),id)).forEach(i->i.setReviewStatus(status));}

    private UploadResult process(Dataset data,String filename,Map<String,String> mapping){
        Set<String> seen=new HashSet<>();List<List<String>> removed=new ArrayList<>(),rejected=new ArrayList<>();

        List<Invoice> batch=new ArrayList<>();List<Map<String,String>> sourceRows=new ArrayList<>();int rowNo=1;
        for(Map<String,String> row:data.rows){rowNo++;try{
            Invoice invoice=mapInvoice(row,mapping);
            String key=duplicateKey(invoice.getVendor(),invoice.getInvoiceNumber());
            if(!seen.add(key)){removed.add(List.of(invoice.getInvoiceNumber(),invoice.getVendor(),String.valueOf(invoice.getInvoiceDate()),invoice.getTotalAmount().toPlainString(),"Repeated vendor + normalized invoice number"));continue;}
            batch.add(invoice);sourceRows.add(row);
        }catch(Exception ex){rejected.add(List.of(Integer.toString(rowNo),(ex.getMessage() == null ? "Invalid row" : ex.getMessage()),String.join(" | ",row.values())));}}

        risk.evaluate(batch);
        for(Invoice invoice:batch){if(safe(invoice))invoice.setReviewStatus("Auto-cleared");}
        invoices.saveAll(batch);invoices.flush();
        int safe=(int)batch.stream().filter(this::safe).count();int review=batch.size()-safe;
        latestInvoices=List.copyOf(batch);
        List<Map<String,String>> cleanedSource=new ArrayList<>();for(int i=0;i<batch.size();i++)if(this.safe(batch.get(i)))cleanedSource.add(sourceRows.get(i));
        latestExcel=buildWorkbook(data.headers,cleanedSource,batch,removed,rejected,filename,safe,review);
        logs.save(new AuditLog("DATASET_IMPORT",filename+" processed; "+batch.size()+" unique invoices, "+removed.size()+" duplicates removed, "+rejected.size()+" invalid rows"));
        UploadResult result=new UploadResult();result.count=batch.size();result.safe=safe;result.review=review;result.duplicates=removed.size();result.rejected=rejected.size();result.message="Processed "+batch.size()+" unique invoices. "+removed.size()+" duplicates removed; "+safe+" zero-risk invoices added to Cleaned Dataset.";
        return result;
    }

    private boolean safe(Invoice i){return i.getRiskScore()==0&&!i.isDisputed();}

    private Invoice mapInvoice(Map<String,String> row,Map<String,String> mapping){
        String number=get(row,mapping,"invoiceNumber"),vendor=get(row,mapping,"vendor"),date=get(row,mapping,"invoiceDate"),amount=get(row,mapping,"totalAmount");
        if(number==null||vendor==null||date==null||amount==null)throw new IllegalArgumentException("Required invoice number, vendor, date, or amount is blank");
        BigDecimal total=money(amount),taxable=optionalMoney(get(row,mapping,"taxableAmount"));
        BigDecimal gst=Optional.ofNullable(optionalMoney(get(row,mapping,"gstAmount"))).orElse(BigDecimal.ZERO);
        BigDecimal rate=Optional.ofNullable(optionalMoney(get(row,mapping,"gstRate"))).orElse(BigDecimal.ZERO);
        if(Objects.equals(mapping.get("totalAmount"),mapping.get("taxableAmount"))&&gst.signum()!=0)total=total.add(gst);
        if(taxable==null)taxable=total.subtract(gst);
        Integer qty=optionalInteger(get(row,mapping,"quantity"));if(qty==null)qty=1;
        String gstin=get(row,mapping,"gstin");Invoice i=new Invoice(number.trim(),vendor.trim(),gstin,parseDate(date),taxable,gst,rate,total,qty);
        i.setMatching(get(row,mapping,"poNumber"),optionalInteger(get(row,mapping,"poQuantity")),optionalMoney(get(row,mapping,"poAmount")),get(row,mapping,"grnNumber"),optionalInteger(get(row,mapping,"receivedQuantity")),optionalMoney(get(row,mapping,"receivedAmount")));
        String currency=get(row,mapping,"currency");if(currency==null)currency=gstin==null?"Unspecified":"INR";
        String status=get(row,mapping,"paymentStatus");if(status==null)status=get(row,mapping,"settledDate")==null?"Unknown":"Paid";
        i.setSourceDetails(currency,status);i.setDisputed(isTrue(get(row,mapping,"disputed")));return i;
    }

    private Dataset readDataset(byte[] bytes,String filename)throws IOException{
        if(filename.toLowerCase(Locale.ROOT).endsWith(".csv")){
            try(Reader reader=new InputStreamReader(new ByteArrayInputStream(bytes),StandardCharsets.UTF_8);CSVParser parser=CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).setTrim(true).build().parse(reader)){
                List<String> headers=new ArrayList<>(parser.getHeaderMap().keySet());List<Map<String,String>> rows=new ArrayList<>();
                for(CSVRecord record:parser){Map<String,String> row=new LinkedHashMap<>();for(String h:headers)row.put(h,record.get(h).trim());rows.add(row);}
                return new Dataset(headers,rows);
            }
        }
        try(Workbook workbook=WorkbookFactory.create(new ByteArrayInputStream(bytes))){
            Sheet sheet=workbook.getSheet("Invoices");if(sheet==null)sheet=workbook.getSheetAt(0);
            if(sheet.getPhysicalNumberOfRows()<2)throw new IllegalArgumentException("Workbook needs a header row and invoice rows.");
            DataFormatter formatter=new DataFormatter();Row header=sheet.getRow(sheet.getFirstRowNum());if(header==null)throw new IllegalArgumentException("Could not find a header row.");
            List<String> headers=new ArrayList<>();for(Cell c:header){String h=formatter.formatCellValue(c).replace("\uFEFF","").trim();if(!h.isBlank())headers.add(h);}
            List<Map<String,String>> rows=new ArrayList<>();for(int r=header.getRowNum()+1;r<=sheet.getLastRowNum();r++){Row source=sheet.getRow(r);if(source==null)continue;Map<String,String> row=new LinkedHashMap<>();boolean any=false;int col=0;for(Cell c:header){String h=formatter.formatCellValue(c).trim();if(!h.isBlank()){String value=formatter.formatCellValue(source.getCell(col)).trim();row.put(h,value);any|=!value.isBlank();}col++;}if(any)rows.add(row);}
            return new Dataset(headers,rows);
        }catch(IllegalArgumentException ex){throw ex;}catch(Exception ex){throw new IllegalArgumentException("Could not read this file. Upload CSV, XLS, or XLSX.",ex);}
    }

    private Map<String,String> detectMapping(List<String> headers){
        Map<String,String> result=new LinkedHashMap<>();
        Map<String,List<String>> aliases=Map.ofEntries(
            Map.entry("invoiceNumber",List.of("invoice_number","invoice_no","invoice_num","invoiceNumber","invoiceId","invoice_id","doc_id","document_id","bill_number","bill_no","reference_number","id")),
            Map.entry("vendor",List.of("vendor","vendor_name","supplier","supplier_name","merchant","customer","customer_id","customerID","customer_number","cust_number","name_customer","counterparty","payee")),
            Map.entry("invoiceDate",List.of("invoice_date","invoiceDate","document_create_date","posting_date","doc_date","date","due_date","due_in_date","clear_date","settled_date")),
            Map.entry("totalAmount",List.of("total_amount","invoice_amount","InvoiceAmount","total_open_amount","amount_due","invoice_value","gross_amount","net_amount","amount","value","total")),
            Map.entry("taxableAmount",List.of("taxable_amount","subtotal","sub_total","net_amount")),
            Map.entry("gstAmount",List.of("gst_amount","tax_amount","vat_amount","tax")),
            Map.entry("gstRate",List.of("gst_rate","tax_rate","vat_rate")),
            Map.entry("quantity",List.of("quantity","qty","units")),Map.entry("gstin",List.of("gstin","vendor_gstin","tax_id")),
            Map.entry("currency",List.of("currency","invoice_currency")),Map.entry("paymentStatus",List.of("payment_status","isOpen","status")),
            Map.entry("disputed",List.of("disputed","is_disputed")),Map.entry("settledDate",List.of("settled_date","SettledDate","clear_date","paid_date")),
            Map.entry("poNumber",List.of("po_number","purchase_order","purchase_order_number")),Map.entry("poQuantity",List.of("po_quantity","ordered_quantity")),
            Map.entry("poAmount",List.of("po_amount","purchase_order_amount")),Map.entry("grnNumber",List.of("grn_number","goods_receipt_number")),
            Map.entry("receivedQuantity",List.of("received_quantity","grn_quantity")),Map.entry("receivedAmount",List.of("received_amount","grn_amount"))
        );
        for(String field:FIELDS){for(String alias:aliases.getOrDefault(field,List.of())){String found=headers.stream().filter(h->normalize(h).equals(normalize(alias))).findFirst().orElse(null);if(found!=null){result.put(field,found);break;}}}
        if(!result.containsKey("totalAmount")&&result.containsKey("taxableAmount"))result.put("totalAmount",result.get("taxableAmount"));
        fuzzyMap(headers,result,"invoiceNumber",List.of("invoicenumber","invoiceno","invoiceid","billnumber","docid"));
        fuzzyMap(headers,result,"vendor",List.of("vendor","supplier","customer","merchant","payee","counterparty"));
        fuzzyMap(headers,result,"invoiceDate",List.of("invoicedate","documentdate","createdate","postingdate","transactiondate"));
        fuzzyMap(headers,result,"totalAmount",List.of("invoiceamount","totalamount","grossamount","netamount","amountdue","totalopenamount"));
        return result;
    }

    private void fuzzyMap(List<String> headers,Map<String,String> map,String field,List<String> tokens){
        if(map.containsKey(field))return;
        String candidate=headers.stream().filter(h->{String n=normalize(h);return tokens.stream().anyMatch(n::contains);}).findFirst().orElse(null);
        if(candidate!=null)map.put(field,candidate);
    }
    private boolean hasRequired(Map<String,String> mapping){return mapping.keySet().containsAll(REQUIRED);}
    private String get(Map<String,String> row,Map<String,String> mapping,String field){String column=mapping.get(field);if(column==null)return null;String value=row.get(column);return value==null||value.isBlank()?null:value.trim();}
    private String normalize(String value){return value==null?"":value.replace("\uFEFF","").trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");}
    private String duplicateKey(String vendor,String number){return normalize(vendor)+"|"+normalize(number);}
    private BigDecimal money(String value){String v=value.trim().replace(",","").replaceAll("[^0-9.()\\-]","");boolean neg=v.startsWith("(")&&v.endsWith(")");v=v.replace("(","").replace(")","");if(v.isBlank())throw new IllegalArgumentException("amount is not numeric: "+value);BigDecimal amount=new BigDecimal(v);return neg?amount.negate():amount;}
    private BigDecimal optionalMoney(String value){return value==null?null:money(value);}
    private Integer optionalInteger(String value){if(value==null)return null;return money(value).intValueExact();}
    private LocalDate parseDate(String value){String v=value.trim();if(v.matches("[0-9]{8}")){try{return LocalDate.parse(v,DateTimeFormatter.BASIC_ISO_DATE);}catch(Exception ignored){}}if(v.length()>=10){try{return LocalDate.parse(v.substring(0,10));}catch(Exception ignored){}}for(DateTimeFormatter f:List.of(DateTimeFormatter.ofPattern("M/d/uuuu"),DateTimeFormatter.ofPattern("d/M/uuuu"),DateTimeFormatter.ofPattern("M-d-uuuu"),DateTimeFormatter.ofPattern("d-M-uuuu"))){try{return LocalDate.parse(v,f);}catch(Exception ignored){}}throw new IllegalArgumentException("date is not recognized: "+value);}
    private boolean isTrue(String value){return value!=null&&Set.of("yes","y","true","1","disputed").contains(value.trim().toLowerCase(Locale.ROOT));}

    private byte[] buildWorkbook(List<String> sourceHeaders,List<Map<String,String>> sourceRows,List<Invoice> batch,List<List<String>> removed,List<List<String>> rejected,String filename,int safe,int review){
        try(Workbook wb=new XSSFWorkbook();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            Sheet summary=wb.createSheet("Import Summary");String[][] stats={{"Input file",filename},{"Unique invoices imported",Integer.toString(batch.size())},{"Zero-risk invoices in cleaned dataset",Integer.toString(safe)},{"Review required",Integer.toString(review)},{"Duplicates removed",Integer.toString(removed.size())},{"Invalid rows rejected",Integer.toString(rejected.size())},{"Rule", "Only invoices with risk score 0 appear in the Cleaned Dataset; duplicates, non-zero risk, and invalid rows are excluded or listed separately."}};
            for(int r=0;r<stats.length;r++){Row row=summary.createRow(r);row.createCell(0).setCellValue(stats[r][0]);row.createCell(1).setCellValue(stats[r][1]);}summary.setColumnWidth(0,8500);summary.setColumnWidth(1,18000);

            List<Invoice> clean=batch.stream().filter(this::safe).toList(),needsReview=batch.stream().filter(i->!safe(i)).toList();
            writeSourceSheet(wb,"Cleaned Dataset",sourceHeaders,sourceRows,clean);
            writeInvoiceSheet(wb,"Final Cleaned Invoices",clean);writeInvoiceSheet(wb,"Review Required",needsReview);
            writeRowsSheet(wb,"Removed Duplicates",List.of("Invoice Number","Vendor","Invoice Date","Amount","Removal Reason"),removed);
            writeRowsSheet(wb,"Rejected Rows",List.of("Source Row","Reason","Original Values"),rejected);
            wb.setSheetOrder("Cleaned Dataset",0);wb.setActiveSheet(0);
            wb.write(out);return out.toByteArray();
        }catch(IOException e){throw new IllegalStateException("Could not build the cleaned Excel report",e);}
    }
    private void writeSourceSheet(Workbook wb,String name,List<String> headers,List<Map<String,String>> rows,List<Invoice> cleanedInvoices){
        if(rows.size()!=cleanedInvoices.size())throw new IllegalStateException("Cleaned source rows and risk results are not aligned.");
        List<String> outputHeaders=new ArrayList<>(headers);outputHeaders.addAll(List.of("Audit Risk Score","Audit Risk Level","Audit Risk Reasons"));
        Sheet sheet=wb.createSheet(name);CellStyle head=headerStyle(wb);Row header=sheet.createRow(0);
        for(int c=0;c<outputHeaders.size();c++){Cell cell=header.createCell(c);cell.setCellValue(outputHeaders.get(c));cell.setCellStyle(head);sheet.setColumnWidth(c,c>=headers.size()?7000:Math.min(12000,Math.max(3500,headers.get(c).length()*300)));}
        int r=1;for(int i=0;i<rows.size();i++){Map<String,String> values=rows.get(i);Invoice invoice=cleanedInvoices.get(i);Row row=sheet.createRow(r++);for(int c=0;c<headers.size();c++){String value=values.get(headers.get(c));if(value!=null)row.createCell(c).setCellValue(value);}row.createCell(headers.size()).setCellValue(invoice.getRiskScore());row.createCell(headers.size()+1).setCellValue(invoice.getRiskLevel());row.createCell(headers.size()+2).setCellValue(invoice.getReasons());}
        sheet.createFreezePane(0,1);if(r>1)sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,r-1,0,outputHeaders.size()-1));
    }    private void writeInvoiceSheet(Workbook wb,String name,List<Invoice> rows){
        Sheet sheet=wb.createSheet(name);String[] headers={"Invoice Number","Vendor","GSTIN","Invoice Date","Taxable Amount","GST Amount","GST Rate","Total Amount","Quantity","Currency","Payment Status","Risk Score","Risk Level","Reasons","Review Status"};
        CellStyle head=headerStyle(wb);Row h=sheet.createRow(0);for(int c=0;c<headers.length;c++){Cell cell=h.createCell(c);cell.setCellValue(headers[c]);cell.setCellStyle(head);sheet.setColumnWidth(c,c==13?11500:4200);}
        int r=1;for(Invoice i:rows){Row row=sheet.createRow(r++);Object[] vals={i.getInvoiceNumber(),i.getVendor(),i.getGstin(),i.getInvoiceDate()==null?"":i.getInvoiceDate().toString(),i.getTaxableAmount(),i.getGstAmount(),i.getGstRate(),i.getTotalAmount(),i.getQuantity(),i.getCurrency(),i.getPaymentStatus(),i.getRiskScore(),i.getRiskLevel(),i.getReasons(),i.getReviewStatus()};
            for(int c=0;c<vals.length;c++){Cell cell=row.createCell(c);Object v=vals[c];if(v==null)cell.setBlank();else if(v instanceof BigDecimal b)cell.setCellValue(b.doubleValue());else if(v instanceof Number n)cell.setCellValue(n.doubleValue());else cell.setCellValue(v.toString());}
        }
        sheet.createFreezePane(0,1);if(r>1)sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,r-1,0,headers.length-1));
    }
    private void writeRowsSheet(Workbook wb,String name,List<String> headers,List<List<String>> rows){Sheet s=wb.createSheet(name);CellStyle head=headerStyle(wb);Row h=s.createRow(0);for(int c=0;c<headers.size();c++){Cell cell=h.createCell(c);cell.setCellValue(headers.get(c));cell.setCellStyle(head);s.setColumnWidth(c,c==headers.size()-1?14000:5500);}int r=1;for(List<String> values:rows){Row row=s.createRow(r++);for(int c=0;c<values.size();c++)row.createCell(c).setCellValue(values.get(c));}s.createFreezePane(0,1);if(r>1)s.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,r-1,0,headers.size()-1));}
    private CellStyle headerStyle(Workbook wb){CellStyle style=wb.createCellStyle();Font f=wb.createFont();f.setBold(true);f.setColor(IndexedColors.WHITE.getIndex());style.setFont(f);style.setFillForegroundColor(IndexedColors.DARK_TEAL.getIndex());style.setFillPattern(FillPatternType.SOLID_FOREGROUND);return style;}

    private record Dataset(List<String> headers,List<Map<String,String>> rows){}
    private record PendingUpload(byte[] bytes,String filename,List<String> headers,Instant createdAt){}
    public static class UploadResult {
        public boolean needsMapping;public String uploadId;public List<String> headers=List.of();public Map<String,String> mapping=Map.of();
        public int count,safe,review,duplicates,rejected;public String message;
    }
}
