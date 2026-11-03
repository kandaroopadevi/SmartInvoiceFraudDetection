package com.invoice;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.*;

@Controller
public class WebController {
    private static final int PAGE_SIZE = 50;
    private static final Set<String> REVIEW_STATUSES = Set.of("Pending", "Investigating", "Cleared", "Escalated");
    private final InvoiceRepository invoices;
    private final AuditLogRepository logs;
    private final InvoiceService service;

    public WebController(InvoiceRepository invoices, AuditLogRepository logs, InvoiceService service) {
        this.invoices = invoices;
        this.logs = logs;
        this.service = service;
    }

    @GetMapping("/")
    public String dashboard(@RequestParam(defaultValue = "") String q,
                            @RequestParam(defaultValue = "ALL") String risk,
                            @RequestParam(defaultValue = "0") int page, Model model) {
        fillDashboard(model, q, risk, page);
        return "dashboard";
    }

    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file, Model model) {
        try {
            InvoiceService.UploadResult result = service.importOrPrepare(file);
            if (result.needsMapping) {
                model.addAttribute("uploadId", result.uploadId);
                model.addAttribute("headers", result.headers);
                model.addAttribute("mapping", result.mapping);
                model.addAttribute("mappingFields", List.of("invoiceNumber", "vendor", "invoiceDate", "totalAmount", "taxableAmount", "gstAmount", "gstRate", "quantity", "gstin", "currency", "paymentStatus", "settledDate", "disputed", "poNumber", "poQuantity", "poAmount", "grnNumber", "receivedQuantity", "receivedAmount"));
                return "column-mapping";
            }
            model.addAttribute("message", result.message);
            addImportCounts(model, result);
        } catch (Exception ex) {
            model.addAttribute("error", message(ex));
        }
        fillDashboard(model, "", "ALL", 0);
        return "dashboard";
    }

    @PostMapping("/upload/mapped")
    public String uploadMapped(@RequestParam Map<String, String> selected, Model model) {
        try {
            Map<String, String> mapping = new HashMap<>(selected);
            String uploadId = mapping.remove("uploadId");
            InvoiceService.UploadResult result = service.importWithMapping(uploadId, mapping);
            model.addAttribute("message", result.message);
            addImportCounts(model, result);
        } catch (Exception ex) {
            model.addAttribute("error", message(ex));
        }
        fillDashboard(model, "", "ALL", 0);
        return "dashboard";
    }

    @PostMapping("/review/{id}")
    public String review(@PathVariable Long id, @RequestParam String status, RedirectAttributes attributes) {
        if (!REVIEW_STATUSES.contains(status)) {
            attributes.addFlashAttribute("error", "Choose a valid review decision.");
            return "redirect:/";
        }
        invoices.findById(id).ifPresent(invoice -> {
            invoice.setReviewStatus(status);
            invoices.save(invoice);
            service.updateLatestReview(id, status);
            logs.save(new AuditLog("REVIEW", invoice.getInvoiceNumber() + " marked " + status));
        });
        attributes.addFlashAttribute("message", "Review decision saved.");
        return "redirect:/";
    }

    @GetMapping("/audit")
    public String audit(Model model) {
        model.addAttribute("logs", logs.findTop100ByOrderByCreatedAtDesc());
        return "audit";
    }

    @GetMapping("/report.xlsx")
    public void excelReport(HttpServletResponse response) throws IOException {
        byte[] bytes = service.latestExcel();
        if (bytes.length == 0) {
            response.sendError(404, "Import a dataset first to create the cleaned Excel report.");
            return;
        }
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition", "attachment; filename=cleaned-invoices.xlsx");
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
        logs.save(new AuditLog("REPORT_EXPORT", "Cleaned invoice Excel downloaded"));
    }

    @GetMapping("/report.csv")
    public void report(HttpServletResponse response) throws IOException {
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=invoice-risk-report.csv");
        var writer = response.getWriter();
        writer.write("invoice_number,vendor,date,total,risk_score,risk_level,reasons,review_status\r\n");
        service.latestInvoices().stream()
                .sorted(Comparator.comparing(Invoice::getRiskScore).reversed()
                        .thenComparing(Invoice::getInvoiceDate, Comparator.nullsLast(Comparator.reverseOrder())))
                .forEach(invoice -> {
                    String line = String.join(",", q(invoice.getInvoiceNumber()), q(invoice.getVendor()),
                            q(String.valueOf(invoice.getInvoiceDate())), q(invoice.getTotalAmount().toPlainString()),
                            q(invoice.getRiskScore().toString()), q(invoice.getRiskLevel()), q(invoice.getReasons()),
                            q(invoice.getReviewStatus()));
                    writer.write(line + "\r\n");
                });
        logs.save(new AuditLog("REPORT_EXPORT", "CSV risk report downloaded"));
    }

    private void fillDashboard(Model model, String query, String riskFilter, int requestedPage) {
        String normalizedRisk = Set.of("LOW", "MEDIUM", "HIGH").contains(riskFilter.toUpperCase(Locale.ROOT))
                ? riskFilter.toUpperCase(Locale.ROOT) : "ALL";
        List<Invoice> all = service.latestInvoices();
        List<Invoice> filtered = all.stream()
                .filter(invoice -> query == null || query.isBlank()
                        || contains(invoice.getInvoiceNumber(), query)
                        || contains(invoice.getVendor(), query)
                        || contains(invoice.getReasons(), query))
                .filter(invoice -> normalizedRisk.equals("ALL") || invoice.getRiskLevel().equals(normalizedRisk))
                .sorted(Comparator.comparing(Invoice::getRiskScore).reversed()
                        .thenComparing(Invoice::getInvoiceDate, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        int pages = Math.max(1, (filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        int from = Math.min(page * PAGE_SIZE, filtered.size());
        int to = Math.min(from + PAGE_SIZE, filtered.size());
        model.addAttribute("invoices", filtered.subList(from, to));
        model.addAttribute("total", all.size());
        model.addAttribute("high", all.stream().filter(i -> i.getRiskLevel().equals("HIGH")).count());
        model.addAttribute("medium", all.stream().filter(i -> i.getRiskLevel().equals("MEDIUM")).count());
        model.addAttribute("flagged", all.stream().filter(i -> i.getRiskScore() > 0).count());
        model.addAttribute("clean", all.stream().filter(i -> i.getRiskScore() == 0).count());
        model.addAttribute("filteredTotal", filtered.size());
        model.addAttribute("page", page);
        model.addAttribute("totalPages", pages);
        model.addAttribute("startRow", filtered.isEmpty() ? 0 : from + 1);
        model.addAttribute("endRow", to);
        model.addAttribute("searchQuery", query == null ? "" : query);
        model.addAttribute("riskFilter", normalizedRisk);
        model.addAttribute("reportReady", service.hasLatestExcel());
    }

    private void addImportCounts(Model model, InvoiceService.UploadResult result) {
        model.addAttribute("imported", result.count);
        model.addAttribute("autoCleared", result.safe);
        model.addAttribute("heldForReview", result.review);
        model.addAttribute("duplicatesRemoved", result.duplicates);
        model.addAttribute("invalidRows", result.rejected);
        model.addAttribute("reportReady", service.hasLatestExcel());
    }

    private boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query.trim().toLowerCase(Locale.ROOT));
    }

    private String message(Exception exception) {
        return exception.getMessage() == null ? "Could not import this file." : exception.getMessage();
    }

    private String q(String value) {
        String escaped = value == null ? "" : value.replace("\"", "\"\"");
        if (escaped.matches("^[=+@\\-].*")) escaped = "'" + escaped;
        return "\"" + escaped + "\"";
    }
}